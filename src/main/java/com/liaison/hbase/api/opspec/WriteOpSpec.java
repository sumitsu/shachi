/**
 * Copyright 2015 Liaison Technologies, Inc.
 * This software is the confidential and proprietary information of
 * Liaison Technologies, Inc. ("Confidential Information").  You shall
 * not disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with Liaison Technologies.
 */
package com.liaison.hbase.api.opspec;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.function.BiConsumer;

import com.liaison.hbase.context.HBaseContext;
import com.liaison.hbase.exception.SpecValidationException;
import com.liaison.hbase.util.StringRepFormat;
import com.liaison.hbase.util.Util;

public final class WriteOpSpec extends TableRowOpSpec<WriteOpSpec> implements WriteOpSpecFluid, WriteOpSpecFrozen, Serializable {

    private static final long serialVersionUID = 2572256818666730468L;

    // ||========================================================================================||
    // ||    INSTANCE PROPERTIES                                                                 ||
    // ||----------------------------------------------------------------------------------------||
    
    private CondSpec<WriteOpSpec> givenCondition;
    private final List<ColSpecWrite<WriteOpSpec>> withColumn;
    
    // ||----(instance properties)---------------------------------------------------------------||
    
    // ||========================================================================================||
    // ||    INSTANCE METHODS: API: FLUID                                                        ||
    // ||----------------------------------------------------------------------------------------||

    @Override
    public RowSpec<WriteOpSpec> on() throws IllegalArgumentException, IllegalStateException {
        final RowSpec<WriteOpSpec> rowSpec;
        rowSpec = new RowSpec<>(this);
        setTableRow(rowSpec);
        return rowSpec;
    }
    
    @Override
    public CondSpec<WriteOpSpec> given() throws IllegalStateException {
        prepMutation();
        Util.validateExactlyOnce("givenCondition", CondSpec.class, this.givenCondition);
        this.givenCondition = new CondSpec<>(this);
        return this.givenCondition;
    }
    
    @Override
    public ColSpecWrite<WriteOpSpec> with() throws IllegalStateException {
        final ColSpecWrite<WriteOpSpec> withCol;
        prepMutation();
        withCol = new ColSpecWrite<>(this);
        this.withColumn.add(withCol);
        return withCol;
    }
    
    public <X> WriteOpSpec withAllOf(final Iterable<X> sourceData, final BiConsumer<X, ColSpecWriteFluid<?>> dataToColumnGenerator) {
        ColSpecWrite<WriteOpSpec> withCol;
        prepMutation();
        if (sourceData != null) {
            for (X element : sourceData) {
                withCol = new ColSpecWrite<>(this);
                dataToColumnGenerator.accept(element, new ColSpecWriteConfined(withCol));
                this.withColumn.add(withCol);
            }
        }
        return self();
    }
    
    // ||----(instance methods: API: fluid)------------------------------------------------------||
    
    // ||========================================================================================||
    // ||    INSTANCE METHODS: API: FROZEN                                                       ||
    // ||----------------------------------------------------------------------------------------||
    
    @Override
    public CondSpec<WriteOpSpec> getGivenCondition() {
        return this.givenCondition;
    }
    
    @Override
    public List<ColSpecWrite<WriteOpSpec>> getWithColumn() {
        return Collections.unmodifiableList(this.withColumn);
    }
    
    // ||----(instance methods: API: frozen)-----------------------------------------------------||
    
    // ||========================================================================================||
    // ||    INSTANCE METHODS: UTILITY                                                           ||
    // ||----------------------------------------------------------------------------------------||
    
    @Override
    public WriteOpSpec self() { return this; }

    @Override
    protected void validate() throws SpecValidationException {
        super.validate();
        Util.validateRequired(getTableRow(), this, "from", RowSpec.class);
        Util.validateAtLeastOne(getWithColumn(), this, "with", ColSpecWrite.class);
    }
    
    @Override
    protected String prepareStrRepHeadline() {
        return "[<<Operation>>:WRITE]";
    }
    
    @Override
    protected void prepareStrRep(final StringBuilder strGen, final StringRepFormat format) {
        final RowSpec<WriteOpSpec> tableRow;
        tableRow = getTableRow();
        if (format == StringRepFormat.STRUCTURED) {
            if (tableRow != null) {
                Util.appendIndented(strGen,
                                    getDepth() + 1,
                                    "on table/row: ",
                                    "\n",
                                    tableRow,
                                    "\n");
            }
            if (this.givenCondition != null) {
                Util.appendIndented(strGen,
                                    getDepth() + 1,
                                    "given condition: ",
                                    "\n",
                                    this.givenCondition,
                                    "\n");
            }
            if (this.withColumn.size() > 0) {
                Util.appendIndented(strGen, getDepth() + 1, "with column(s): ", "\n");
                for (ColSpecWrite<WriteOpSpec> colSpec : this.withColumn) {
                    Util.appendIndented(strGen, getDepth() + 1, colSpec);
                }
            }
        } else if (format == StringRepFormat.INLINE) {
            strGen.append("{");
            if (tableRow != null) {
                Util.append(strGen, "on=", tableRow);
                if ((this.givenCondition != null) && (this.withColumn.size() > 0)) {
                    strGen.append(",");
                }
            }
            if (this.givenCondition != null) {
                Util.append(strGen, "if=", this.givenCondition);
                if (this.withColumn.size() > 0) {
                    strGen.append(",");
                }
            }
            if (this.withColumn.size() > 0) {
                Util.append(strGen, "col=", this.withColumn);
            }
            strGen.append("}");
        }
    }
    
    @Override
    protected boolean deepEquals(final OperationSpec<?> otherOpSpec) {
        final WriteOpSpec otherWriteSpec;
        if (otherOpSpec instanceof WriteOpSpec) {
            otherWriteSpec = (WriteOpSpec) otherOpSpec;
            /*
             * Equality checks ordered from least to most expensive, to allow for relatively quick
             * short-circuiting
             */
            return ((Util.refEquals(this.getTableRow(), otherWriteSpec.getTableRow()))
                    &&
                    (Util.refEquals(this.givenCondition, otherWriteSpec.givenCondition))
                    &&
                    (Util.refEquals(this.withColumn, otherWriteSpec.withColumn)));
        }
        return false;
    }
    
    // ||----(instance methods: utility)---------------------------------------------------------||

    // ||========================================================================================||
    // ||    CONSTRUCTORS                                                                        ||
    // ||----------------------------------------------------------------------------------------||

    public WriteOpSpec(final Object handle, final HBaseContext context, final OperationControllerDefault parent) {
        super(handle, context, parent);
        this.withColumn = new LinkedList<>();
    }
    
    // ||----(constructors)----------------------------------------------------------------------||
}
