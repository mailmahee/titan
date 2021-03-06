package com.thinkaurelius.titan.graphdb.transaction;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Multimap;
import com.thinkaurelius.titan.core.InvalidElementException;
import com.thinkaurelius.titan.core.TitanEdge;
import com.thinkaurelius.titan.core.TitanException;
import com.thinkaurelius.titan.core.TitanKey;
import com.thinkaurelius.titan.core.TitanLabel;
import com.thinkaurelius.titan.core.TitanProperty;
import com.thinkaurelius.titan.core.TitanQuery;
import com.thinkaurelius.titan.core.TitanRelation;
import com.thinkaurelius.titan.core.TitanType;
import com.thinkaurelius.titan.core.TitanVertex;
import com.thinkaurelius.titan.core.TypeMaker;
import com.thinkaurelius.titan.graphdb.blueprints.TitanBlueprintsTransaction;
import com.thinkaurelius.titan.graphdb.database.InternalTitanGraph;
import com.thinkaurelius.titan.graphdb.idmanagement.IDInspector;
import com.thinkaurelius.titan.graphdb.query.SimpleTitanQuery;
import com.thinkaurelius.titan.graphdb.relations.AttributeUtil;
import com.thinkaurelius.titan.graphdb.relations.InternalRelation;
import com.thinkaurelius.titan.graphdb.relations.factory.RelationFactory;
import com.thinkaurelius.titan.graphdb.types.InternalTitanType;
import com.thinkaurelius.titan.graphdb.types.manager.TypeManager;
import com.thinkaurelius.titan.graphdb.types.system.SystemKey;
import com.thinkaurelius.titan.graphdb.types.system.SystemType;
import com.thinkaurelius.titan.graphdb.util.VertexCentricEdgeIterable;
import com.thinkaurelius.titan.graphdb.vertices.InternalTitanVertex;
import com.thinkaurelius.titan.graphdb.vertices.factory.VertexFactory;
import com.thinkaurelius.titan.util.datastructures.Factory;
import com.thinkaurelius.titan.util.datastructures.IterablesUtil;
import com.thinkaurelius.titan.util.datastructures.Maps;
import com.tinkerpop.blueprints.Edge;
import com.tinkerpop.blueprints.Vertex;
import com.tinkerpop.blueprints.util.PropertyFilteredIterable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public abstract class AbstractTitanTx extends TitanBlueprintsTransaction implements InternalTitanTransaction {

    @SuppressWarnings("unused")
    private static final Logger log = LoggerFactory.getLogger(AbstractTitanTx.class);

    protected InternalTitanGraph graphdb;

    protected final TypeManager etManager;
    protected final VertexFactory vertexFactory;
    protected final RelationFactory edgeFactory;

    private final ConcurrentMap<TitanKey, ConcurrentMap<Object, TitanVertex>> keyIndex;
    private final Lock keyedPropertyCreateLock;
    private final ConcurrentMap<TitanKey, Multimap<Object, TitanVertex>> attributeIndex;

    private final Optional<Set<InternalTitanVertex>> newVertices;
    private VertexCache vertexCache;

    private boolean isOpen;
    private final TransactionConfig config;

    public AbstractTitanTx(InternalTitanGraph g, VertexFactory vertexFac, RelationFactory edgeFac,
                           TypeManager etManage, TransactionConfig config) {
        graphdb = g;
        etManager = etManage;
        vertexFactory = vertexFac;
        edgeFactory = edgeFac;
        edgeFactory.setTransaction(this);

        this.config = config;
        isOpen = true;

        if (!config.isReadOnly() && config.hasMaintainNewVertices()) {
            newVertices = Optional.of(Collections.newSetFromMap(new ConcurrentHashMap<InternalTitanVertex, Boolean>(10,
                    0.75f, 2)));
        } else {
            newVertices = Optional.absent();
        }
        vertexCache = new StandardVertexCache();

        keyIndex = new ConcurrentHashMap<TitanKey, ConcurrentMap<Object, TitanVertex>>(20, 0.75f, 2);
        attributeIndex = new ConcurrentHashMap<TitanKey, Multimap<Object, TitanVertex>>(20, 0.75f, 2);
        keyedPropertyCreateLock = new ReentrantLock();
    }

    protected final void verifyWriteAccess(TitanVertex... vertices) {
        if (config.isReadOnly())
            throw new UnsupportedOperationException("Cannot create new entities in read-only transaction");
        verifyAccess(vertices);
    }

    @Override
    public final void verifyAccess(TitanVertex... vertices) {
        verifyOpen();
        for (TitanVertex v : vertices) {
            if (!(v instanceof SystemType) && !this.equals(((InternalTitanVertex) v).getTransaction()))
                throw new IllegalArgumentException("The vertex or type is not associated with this transaction [" + v + "]");
            if (!v.isAvailable())
                throw new IllegalArgumentException("The vertex or type is now longer available [" + v + "]");
        }
    }

    private final void verifyOpen() {
        if (isClosed())
            throw TitanException.transactionNotOpenException();
    }

    /*
     * ------------------------------------ TitanVertex and TitanRelation creation ------------------------------------
     */

    @Override
    public void registerNewEntity(InternalTitanVertex n) {
        assert (!(n instanceof InternalRelation) || !((InternalRelation) n).isInline());
        assert n.isNew();
        assert !n.hasID();

        boolean isNode = !(n instanceof InternalRelation);
        if (config.hasAssignIDsImmediately()) {
            graphdb.assignID(n);
            if (isNode)
                vertexCache.add(n, n.getID());
        }
        if (isNode && newVertices.isPresent()) {
            newVertices.get().add(n);
        }
    }

    @Override
    public TitanVertex addVertex() {
        verifyWriteAccess();
        InternalTitanVertex n = vertexFactory.createNew(this);
        return n;
    }

    @Override
    public boolean containsVertex(long id) {
        verifyOpen();
        if (vertexCache.contains(id) && !vertexCache.get(id).isRemoved())
            return true;
        else
            return false;
    }

    @Override
    public TitanVertex getVertex(long id) {
        verifyOpen();
        if (getTxConfiguration().hasVerifyNodeExistence() && !containsVertex(id))
            return null;
        return getExistingVertex(id);
    }

    @Override
    public InternalTitanVertex getExistingVertex(long id) {
        return getExisting(id);
    }

    private InternalTitanVertex getExisting(long id) {
        synchronized (vertexCache) {
            InternalTitanVertex vertex = vertexCache.get(id);
            if (vertex == null) {
                IDInspector idspec = graphdb.getIDInspector();

                if (idspec.isEdgeTypeID(id)) {
                    vertex = etManager.getType(id, this);
                } else if (graphdb.isReferenceVertexID(id)) {
                    throw new UnsupportedOperationException("Reference vertices are currently not supported");
                } else if (idspec.isNodeID(id)) {
                    vertex = vertexFactory.createExisting(this, id);
                } else
                    throw new IllegalArgumentException("ID could not be recognized");
                vertexCache.add(vertex, id);
            } else if (vertex.isRemoved()) throw new IllegalArgumentException("Vertex has been removed for id: " + id);
            return vertex;
        }
    }

    public boolean isDeletedVertex(long id) {
        InternalTitanVertex vertex = vertexCache.get(id);
        return vertex!=null && vertex.isRemoved();
    }

    @Override
    public void deleteVertex(InternalTitanVertex n) {
        verifyWriteAccess(n);
        if (n.hasID()) {
            assert vertexCache.contains(n.getID());
        }
        if (n.isNew() && newVertices.isPresent()) {
            assert newVertices.get().contains(n);
            newVertices.get().remove(n);
        }
    }

    @Override
    public TitanProperty addProperty(TitanVertex vertex, TitanKey key, Object attribute) {
        verifyWriteAccess(vertex, key);
        // Check that attribute of keyed propertyType is unique and lock if so
        final boolean isUniqueKey = key.isUnique() && !(vertex instanceof TitanRelation);
        if (isUniqueKey)
            keyedPropertyCreateLock.lock();
        InternalRelation e = null;
        try {
            if (isUniqueKey && config.hasVerifyKeyUniqueness() && getVertex(key, attribute) != null) {
                throw new InvalidElementException(
                        "The specified attribute is already used for the given property key: " + attribute, vertex);
            }
            e = edgeFactory.createNewProperty(key, (InternalTitanVertex) vertex, attribute);
            addedRelation(e);
        } finally {
            if (isUniqueKey)
                keyedPropertyCreateLock.unlock();
        }
        Preconditions.checkNotNull(e);
        return (TitanProperty) e;
    }

    @Override
    public TitanProperty addProperty(TitanVertex vertex, String key, Object attribute) {
        return addProperty(vertex, getPropertyKey(key), attribute);
    }


    @Override
    public TitanEdge addEdge(TitanVertex outVertex, TitanVertex inVertex, TitanLabel label) {
        verifyWriteAccess(outVertex, inVertex, label);
        InternalRelation e = edgeFactory.createNewRelationship(label, (InternalTitanVertex) outVertex, (InternalTitanVertex) inVertex);
        addedRelation(e);
        return (TitanEdge) e;
    }

    @Override
    public TitanEdge addEdge(TitanVertex outVertex, TitanVertex inVertex, String label) {
        return addEdge(outVertex, inVertex, getEdgeLabel(label));
    }

    @Override
    public TypeMaker makeType() {
        verifyWriteAccess();
        return etManager.getTypeMaker(this);
    }

    @Override
    public boolean containsType(String name) {
        verifyOpen();
        if (keyIndex.containsKey(SystemKey.TypeName) && keyIndex.get(SystemKey.TypeName).containsKey(name)) {
            return true;
        } else {
            return etManager.containsType(name, this);
        }
    }

    @Override
    public TitanType getType(String name) {
        verifyOpen();
        TitanType et = null;
        if (keyIndex.containsKey(SystemKey.TypeName)) {
            Map<Object, TitanVertex> subindex = keyIndex.get(SystemKey.TypeName);
            et = (TitanType) subindex.get(name);
        }
        if (et == null) {
            // Second, check TypeManager
            InternalTitanType eti = etManager.getType(name, this);
            if (eti != null)
                vertexCache.add(eti, eti.getID());
            et = eti;
        }
        return et;
    }

    @Override
    public TitanKey getPropertyKey(String name) {
        TitanType et = getType(name);
        if (et == null) {
            return config.getAutoEdgeTypeMaker().makeKey(name, makeType());
        } else if (et.isPropertyKey()) {
            return (TitanKey) et;
        } else
            throw new IllegalArgumentException("The type of given name is not a key: " + name);

    }

    @Override
    public TitanLabel getEdgeLabel(String name) {
        TitanType et = getType(name);
        if (et == null) {
            return config.getAutoEdgeTypeMaker().makeLabel(name, makeType());
        } else if (et.isEdgeLabel()) {
            return (TitanLabel) et;
        } else
            throw new IllegalArgumentException("The type of given name is not a label: " + name);
    }

    @Override
    public void addedRelation(InternalRelation relation) {
        verifyWriteAccess();
        Preconditions.checkArgument(relation.isNew());
    }

    @Override
    public void deletedRelation(InternalRelation relation) {
        verifyWriteAccess();
        if (relation.isProperty() && !relation.isRemoved() && !relation.isInline()) {
            TitanProperty prop = (TitanProperty) relation;
            if (prop.getPropertyKey().hasIndex()) {
                removeKeyFromIndex(prop);
            }
        }
    }

    @Override
    public TitanQuery query(InternalTitanVertex n) {
        return new SimpleTitanQuery(n);
    }

    @Override
    public TitanQuery query(long nodeid) {
        return new SimpleTitanQuery((InternalTitanVertex) getVertex(nodeid));
    }

    @Override
    public Iterable<Vertex> getVertices() {
        if (newVertices.isPresent())
            return (Iterable) Iterables.filter(newVertices.get(), new Predicate<InternalTitanVertex>() {
                @Override
                public boolean apply(@Nullable InternalTitanVertex internalTitanVertex) {
                    return !(internalTitanVertex instanceof TitanType);
                }
            });
        else
            return IterablesUtil.emptyIterable();
    }

    @Override
    public Iterable<Edge> getEdges() {
        return new VertexCentricEdgeIterable(getVertices());
    }


    @Override
    public void loadedRelation(InternalRelation relation) {
        if (relation.isProperty() && !relation.isInline()) {
            TitanProperty prop = (TitanProperty) relation;
            if (prop.getPropertyKey().hasIndex()) {
                addProperty2Index(prop);
            }
        }
    }


    /*
     * ----------------------------------------------- Index Handling ----------------------------------------------
     */

    private void addProperty2Index(TitanProperty property) {
        addProperty2Index(property.getPropertyKey(), property.getAttribute(), property.getVertex());
    }

    private static Factory<ConcurrentMap<Object, TitanVertex>> keyIndexFactory = new Factory<ConcurrentMap<Object, TitanVertex>>() {
        @Override
        public ConcurrentMap<Object, TitanVertex> create() {
            return new ConcurrentHashMap<Object, TitanVertex>(10, 0.75f, 4);
        }
    };

    private static Factory<Multimap<Object, TitanVertex>> attributeIndexFactory = new Factory<Multimap<Object, TitanVertex>>() {
        @Override
        public Multimap<Object, TitanVertex> create() {
            Multimap<Object, TitanVertex> map = ArrayListMultimap.create(10, 20);
            return map;
            // return Multimaps.synchronizedSetMultimap(map);
        }
    };

    protected void addProperty2Index(TitanKey key, Object att, TitanVertex vertex) {
        Preconditions.checkArgument(key.hasIndex());
        if (key.isUnique()) {
            // TODO ignore NO-ENTRTY
            ConcurrentMap<Object, TitanVertex> subindex = Maps.putIfAbsent(keyIndex, key, keyIndexFactory);

            TitanVertex oth = subindex.putIfAbsent(att, vertex);
            if (oth != null && !oth.equals(vertex)) {
                throw new IllegalArgumentException("The value is already used by another vertex and the key is unique");
            }
        } else {
            Multimap<Object, TitanVertex> subindex = Maps.putIfAbsent(attributeIndex, key, attributeIndexFactory);
            subindex.put(att, vertex);
        }
    }

    private void removeKeyFromIndex(TitanProperty property) {
        Preconditions.checkArgument(property.getPropertyKey().hasIndex());

        TitanKey type = property.getPropertyKey();
        if (type.isUnique()) {
            Map<Object, TitanVertex> subindex = keyIndex.get(type);
            Preconditions.checkNotNull(subindex);
            TitanVertex n = subindex.remove(property.getAttribute());
            assert n != null && n.equals(property.getVertex());
            // TODO Set to NO-ENTRY node object
        } else {
            boolean hasIdenticalProperty = false;
            for (TitanProperty p2 : property.getVertex().getProperties(type)) {
                if (!p2.equals(property) && p2.getAttribute().equals(property.getAttribute())) {
                    hasIdenticalProperty = true;
                    break;
                }
            }
            if (!hasIdenticalProperty) {
                Multimap<Object, TitanVertex> subindex = attributeIndex.get(type);
                Preconditions.checkNotNull(subindex);
                boolean removed = subindex.remove(property.getAttribute(), property.getVertex());
                assert removed;
            }
        }

    }

    // #### Keyed Properties #####

    @Override
    public TitanVertex getVertex(TitanKey key, Object value) {
        verifyAccess(key);
        Preconditions.checkNotNull(key);
        Preconditions.checkArgument(key.isUnique(), "Key is not declared unique");
        value = AttributeUtil.prepareAttribute(value, key.getDataType());
        if (!keyIndex.containsKey(key)) {
            return null;
        } else {
            // TODO: check for NO-ENTRY and return null
            Map<Object, TitanVertex> subindex = keyIndex.get(key);
            return subindex.get(value);
        }
    }

    @Override
    public TitanVertex getVertex(String type, Object value) {
        if (!containsType(type))
            return null;
        return getVertex(getPropertyKey(type), value);
    }

    // #### General Indexed Properties #####

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public Iterable<Vertex> getVertices(String key, Object attribute) {
        Preconditions.checkNotNull(key);
        if (!containsType(key))
            return ImmutableSet.of();
        TitanKey tkey = getPropertyKey(key);
        return (Iterable) getVertices(tkey, attribute);
        // return new PropertyFilteredIterable<Vertex>(key,attribute,getVertices());
    }

    @Override
    public Iterable<TitanVertex> getVertices(final TitanKey key, Object attribute) {
        verifyAccess(key);
        Preconditions.checkNotNull(key);
        attribute = AttributeUtil.prepareAttribute(attribute, key.getDataType());
        if (key.hasIndex()) {
            // First, get stuff from disk
            long[] nodeids = getVertexIDsFromDisk(key, attribute);
            Set<TitanVertex> vertices = new HashSet<TitanVertex>(nodeids.length);
            for (int i = 0; i < nodeids.length; i++) {
                if (!isDeletedVertex(nodeids[i])) {
                    vertices.add(getExistingVertex(nodeids[i]));
                }
            }

            // Next, the in-memory stuff
            Multimap<Object, TitanVertex> attrSubindex = attributeIndex.get(key);
            if (attrSubindex != null) {
                vertices.addAll(attrSubindex.get(attribute));
            }
            Map<Object, TitanVertex> keySubindex = keyIndex.get(key);
            if (keySubindex != null) {
                TitanVertex vertex = keySubindex.get(attribute);
                if (vertex != null) {
                    vertices.add(vertex);
                }
            }
            return vertices;
        } else {
            log.warn("getVertices is invoked with a non-indexed key [" + key.getName() + "] which requires a full database scan. Create key-indexes for better performance.");
            return Iterables.filter(new PropertyFilteredIterable<Vertex>(key.getName(), attribute, this.getVertices()), TitanVertex.class);
        }
    }

    /*
     * --------------------------------------------- Transaction Handling ---------------------------------------------
     */

    private void close() {
        vertexCache.close();
        keyIndex.clear();
        isOpen = false;
    }

    @Override
    public synchronized void commit() {
        close();
    }

    @Override
    public synchronized void rollback() {
        close();
    }

    @Override
    public boolean isOpen() {
        return isOpen;
    }

    @Override
    public boolean isClosed() {
        return !isOpen;
    }

    @Override
    public TransactionConfig getTxConfiguration() {
        return config;
    }

}
