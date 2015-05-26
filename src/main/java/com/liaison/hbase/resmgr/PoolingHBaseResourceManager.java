package com.liaison.hbase.resmgr;

import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HTable;

import com.liaison.hbase.context.HBaseContext;
import com.liaison.hbase.exception.HBaseResourceAcquisitionException;
import com.liaison.hbase.exception.HBaseResourceReleaseException;
import com.liaison.hbase.model.TableModel;
import com.liaison.hbase.resmgr.res.ManagedAdmin;
import com.liaison.hbase.resmgr.res.ManagedTable;

public enum PoolingHBaseResourceManager implements HBaseResourceManager {
    INSTANCE;

    @Override
    public ManagedTable borrow(final HBaseContext context, final TableModel model) throws HBaseResourceAcquisitionException, IllegalArgumentException {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void release(final HBaseContext context, final TableModel model, final HTable table) throws HBaseResourceReleaseException, IllegalArgumentException {
        // TODO
    }

    @Override
    public ManagedAdmin borrowAdmin(final HBaseContext context) throws HBaseResourceAcquisitionException, IllegalArgumentException {
        // TODO
        return null;
    }

    @Override
    public void releaseAdmin(final HBaseContext context, final HBaseAdmin admin) throws HBaseResourceReleaseException, IllegalArgumentException {
        // TODO Auto-generated method stub
    }
}