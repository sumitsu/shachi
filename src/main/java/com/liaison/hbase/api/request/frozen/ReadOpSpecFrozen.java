/**
 * Copyright 2015 Liaison Technologies, Inc.
 * This software is the confidential and proprietary information of
 * Liaison Technologies, Inc. ("Confidential Information").  You shall
 * not disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with Liaison Technologies.
 */
package com.liaison.hbase.api.request.frozen;

import com.liaison.hbase.api.request.impl.ReadOpSpecDefault;
import com.liaison.hbase.dto.FamilyQualifierPair;
import com.liaison.hbase.model.FamilyModel;
import com.liaison.hbase.model.VersioningModel;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * TODO
 * @author Branden Smith; Liaison Technologies, Inc.
 */
public interface ReadOpSpecFrozen extends TableRowOpSpecFrozen<ReadOpSpecDefault> {

    /**
     * TODO
     * @return
     * @throws IllegalStateException
     */
    public EnumSet<VersioningModel> getCommonVersioningConfig() throws IllegalStateException;

    /**
     * TODO
     * @return
     * @throws IllegalStateException
     */
    public LongValueSpecFrozen getCommonVersion() throws IllegalStateException;

    /**
     * TODO
     * @return
     */
    Integer getMaxEntriesPerFamily();

    /**
     * TODO
     * @return
     */
    LongValueSpecFrozen getAtTime();

    /**
     * TODO
     * @return
     */
    List<? extends ColSpecReadFrozen> getWithColumn();

    /**
     * TODO
     * @param famModel
     * @param colSpecRead
     */
    public void addColumnAssoc(FamilyModel famModel, ColSpecReadFrozen colSpecRead);

    /**
     * TODO
     * @param fqp
     * @param colSpecRead
     */
    public void addColumnAssoc(FamilyQualifierPair fqp, ColSpecReadFrozen colSpecRead);

    /**
     * TODO
     * @param famModel
     * @return
     */
    public Set<ColSpecReadFrozen> getColumnAssoc(FamilyModel famModel);

    /**
     * TODO
     * @param fqp
     * @return
     */
    public Set<ColSpecReadFrozen> getColumnAssoc(FamilyQualifierPair fqp);
}
