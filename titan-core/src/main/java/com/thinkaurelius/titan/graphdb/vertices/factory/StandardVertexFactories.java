package com.thinkaurelius.titan.graphdb.vertices.factory;

import com.thinkaurelius.titan.graphdb.adjacencylist.StandardAdjListFactory;
import com.thinkaurelius.titan.graphdb.transaction.InternalTitanTransaction;
import com.thinkaurelius.titan.graphdb.types.system.SystemKey;
import com.thinkaurelius.titan.graphdb.vertices.InternalTitanVertex;
import com.thinkaurelius.titan.graphdb.vertices.PersistStandardTitanVertex;

public enum StandardVertexFactories implements VertexFactory {

    DefaultPersisted {
        @Override
        public InternalTitanVertex createExisting(InternalTitanTransaction tx, long id) {
            return new PersistStandardTitanVertex(tx, StandardAdjListFactory.INSTANCE, id);
        }


        @Override
        public InternalTitanVertex createNew(InternalTitanTransaction tx) {
            InternalTitanVertex n = new PersistStandardTitanVertex(tx, StandardAdjListFactory.INSTANCE);
            n.addProperty(SystemKey.VertexState, (byte) 0);
            tx.registerNewEntity(n);
            return n;
        }


    },

    DefaultInMemory {
        @Override
        public InternalTitanVertex createExisting(InternalTitanTransaction tx, long id) {
            throw new UnsupportedOperationException("Cannot create existing vertices for in-memory transaction");
        }


        @Override
        public InternalTitanVertex createNew(InternalTitanTransaction tx) {
            //InternalTitanVertex n = new StandardTitanVertex(tx,InitialAdjListFactory.BasicFactory);
            InternalTitanVertex n = new PersistStandardTitanVertex(tx, StandardAdjListFactory.INSTANCE);
            tx.registerNewEntity(n);
            return n;
        }

    };


}
