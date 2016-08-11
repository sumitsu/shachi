/**
 * Copyright 2015 Liaison Technologies, Inc.
 * This software is the confidential and proprietary information of
 * Liaison Technologies, Inc. ("Confidential Information").  You shall
 * not disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with Liaison Technologies.
 */
package com.liaison.shachi.api.request.impl;

import com.liaison.javabasics.commons.Util;
import com.liaison.shachi.api.request.fluid.ColSpecReadFluid;
import com.liaison.shachi.model.FamilyHB;
import com.liaison.shachi.model.QualHB;
import com.liaison.shachi.util.TreeNodeRoot;


/**
 * TODO
 * @author Branden Smith; Liaison Technologies, Inc.
 */
public class ColSpecReadConfined extends TreeNodeRoot<ColSpecReadConfined> implements ColSpecReadFluid<ColSpecReadConfined> {

    private final ColSpecRead<ReadOpSpecDefault> colSpecRead;

    @Override
    public ColSpecReadConfined handle(Object handle) throws IllegalStateException {
        colSpecRead.handle(handle);
        return self();
    }
    @Override
    public ColSpecReadConfined fam(final FamilyHB family) throws IllegalStateException, IllegalArgumentException {
        colSpecRead.fam(family);
        return self();
    }
    @Override
    public ColSpecReadConfined qual(final QualHB qual) throws IllegalStateException, IllegalArgumentException {
        colSpecRead.qual(qual);
        return self();
    }
    @Override
    public ColSpecReadConfined optional() throws IllegalStateException, IllegalArgumentException {
        colSpecRead.optional();
        return self();
    }
    @Override
    public LongValueSpecConfinedParent<ColSpecReadConfined, ColSpecRead<ReadOpSpecDefault>> version() throws IllegalStateException, IllegalArgumentException {
        return
            new LongValueSpecConfinedParent<ColSpecReadConfined,
                                            ColSpecRead<ReadOpSpecDefault>>
                    (self(), colSpecRead.version());
    }
    @Override
    public ColSpecReadConfined version(final long version) throws IllegalStateException, IllegalArgumentException {
        return self();
    }

    @Override
    protected ColSpecReadConfined self() {
        return this;
    }
    
    public ColSpecReadConfined(final ColSpecRead<ReadOpSpecDefault> colSpecRead) {
        Util.ensureNotNull(colSpecRead, this, "colSpecRead", ColSpecRead.class);
        this.colSpecRead = colSpecRead;
    }
}