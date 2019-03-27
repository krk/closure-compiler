/*
 * Copyright 2004 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.javascript.jscomp;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/**
 * Tests for {@link PeepholeUnfuck} in isolation. Tests for the interaction of multiple peephole
 * passes are in PeepholeIntegrationTest.
 */
@RunWith(JUnit4.class)
public final class PeepholeUnfuckTest extends CompilerTestCase {

  public PeepholeUnfuckTest() {
    super(DEFAULT_EXTERNS);
  }

  @Override
  @Before
  public void setUp() throws Exception {
    super.setUp();
    disableTypeCheck();
  }

  @Override
  protected CompilerPass getProcessor(final Compiler compiler) {
    PeepholeOptimizationsPass peepholePass =
        new PeepholeOptimizationsPass(compiler, getName(), new PeepholeUnfuck());
    peepholePass.setRetraverseOnChange(false);
    return peepholePass;
  }

  @Override
  protected int getNumRepetitions() {
    return 1;
  }

  private void fold(String js, String expected) {
    test(js, expected);
  }

  /** Check that removing blocks with 1 child works */
  @Test
  public void testFold() {
    fold("[][[]]", "undefined");
    fold("var a =[ [][[]] ]", "var a=[undefined]");
  }

  @Test
  public void testFoldAssignments() {
    fold("var a = [][[]]", "var a=undefined");
  }
}
