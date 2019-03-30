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
import com.google.javascript.jscomp.CompilerOptions.LanguageMode;

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

  private void foldSame(String js) {
    test(js, js);
  }

  @Test
  public void testUndefined() {
    fold("[][[]]", "undefined");
    fold("var a =[ [][[]] ]", "var a=[undefined]");
    fold("var a = [][[]]", "var a=undefined");
  }

  @Test
  public void testStringIndexedString() {
    fold("\"0\"[\"0\"]", "\"0\"");
    fold("\"falseundefined\"[\"10\"]", "\"i\"");

    foldSame("\"falseundefined\"[\"-10\"]");
    foldSame("\"falseundefined\"[\"42\"]");
    foldSame("\"falseundefined\"[\"a\"]");
    foldSame("9999[\"2\"]");
  }

  @Test
  public void testPeepholeUnfuck_FilterConstructorInvocation() {
    foldSame("[].filter[\"constructor\"]()");
    foldSame("[].filter[\"constructor\"](\"1\")");
    foldSame("[].filter[\"constructor\"](1)()");

    fold("[].filter[\"constructor\"](\"1\")()", "eval(\"1\")");
  }

  @Test
  public void testArrayLiteralFunctionStringCoercion() {
    foldSame("[].filter");
    foldSame("+[].filter");

    fold("1+[].filter", "1function filter() {\n [native code]\n}");
    fold("!1+[].filter", "falsefunction filter() {\n [native code]\n}");
    fold("!0+[].concat", "truefunction concat() {\n [native code]\n}");
    fold("\"abc\"+[].propertyIsEnumerable",
        "truefunction propertyIsEnumerable() {\n [native code]\n}");
    fold("1.256+[].filter", "1.256function filter() {\n [native code]\n}");
    fold("[]+[].filter", "function filter() {\n [native code]\n}");

    fold("[].filter+1", "function filter() {\n [native code]\n}1");
    fold("[].filter+!1", "function filter() {\n [native code]\n}false");
    fold("[].filter+!0", "function filter() {\n [native code]\n}true");
    fold("[].filter+\"abc\"", "function filter() {\n [native code]\n}abc");
    fold("[].filter+1.256", "function filter() {\n [native code]\n}1.256");
    fold("[].filter+[]", "function filter() {\n [native code]\n}");
  }
}
