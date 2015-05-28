/**
 * Copyright 2015 Liaison Technologies, Inc.
 * This software is the confidential and proprietary information of
 * Liaison Technologies, Inc. ("Confidential Information").  You shall
 * not disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with Liaison Technologies.
 */
package com.liaison.hbase.api.opspec;

import com.liaison.hbase.model.FamilyModel;
import com.liaison.hbase.model.QualModel;
import com.liaison.hbase.util.Util;

/**
 * TODO
 * @author Branden Smith; Liaison Technologies, Inc.
 */
public class ColSpecReadConfined implements ColSpecReadFluid<ColSpecReadConfined> {

    private final ColSpecRead<ReadOpSpec> colSpecRead;
    
    public ColSpecReadConfined fam(final FamilyModel family) throws IllegalStateException, IllegalArgumentException {
        colSpecRead.fam(family);
        return this;
    }
    public ColSpecReadConfined qual(final QualModel qual) throws IllegalStateException, IllegalArgumentException {
        colSpecRead.qual(qual);
        return this;
    }
    public ColSpecReadConfined optional() throws IllegalStateException, IllegalArgumentException {
        colSpecRead.optional();
        return this;
    }
    
    public ColSpecReadConfined(final ColSpecRead<ReadOpSpec> colSpecRead) {
        Util.ensureNotNull(colSpecRead, this, "colSpecRead", ColSpecRead.class);
        this.colSpecRead = colSpecRead;
    }

}
