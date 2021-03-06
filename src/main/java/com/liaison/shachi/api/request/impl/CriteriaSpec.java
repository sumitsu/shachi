/*
 * Copyright © 2016 Liaison Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.liaison.shachi.api.request.impl;

import com.liaison.shachi.api.request.fluid.CriteriaSpecFluid;
import com.liaison.shachi.util.TreeNode;

import java.io.Serializable;

public abstract class CriteriaSpec<C extends CriteriaSpec<C, P>, P extends TreeNode<P>> extends StatefulSpec<C, P> implements CriteriaSpecFluid<P>, Serializable {
    
    private static final long serialVersionUID = 7087926388191014497L;
    
    public P and() {
        return getParent();
    }

    public CriteriaSpec(final P parent) throws IllegalArgumentException {
        super(parent);
    }
}
