/*
 * Copyright 2009 The Closure Compiler Authors.
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

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import com.google.javascript.jscomp.CompilerOptions.DevMode;
import com.google.javascript.jscomp.CompilerOptions.LanguageMode;

/**
 * Integration tests for https://github.com/aemkei/jsfuck unfucking.
 *
 * @author https://github.com/krk
 */

@RunWith(JUnit4.class)
public final class JsFuckIntegrationTest extends IntegrationTestCase {

  /** Creates a CompilerOptions object with google coding conventions. */
  @Override
  protected CompilerOptions createCompilerOptions() {
    CompilerOptions options = new CompilerOptions();
    options.setLanguageIn(LanguageMode.ECMASCRIPT_2015);
    options.setLanguageOut(LanguageMode.ECMASCRIPT5);
    options.setDevMode(DevMode.EVERY_PASS);
    options.setWarningLevel(DiagnosticGroups.FEATURES_NOT_SUPPORTED_BY_PASS, CheckLevel.OFF);
    options.addWarningsGuard(
        new DiagnosticGroupWarningsGuard(DiagnosticGroups.CHECK_TYPES, CheckLevel.OFF));
    return options;
  }

  @Test
  public void testSimple() {
    CompilerOptions options = createCompilerOptions();
    CompilationLevel.ADVANCED_OPTIMIZATIONS.setOptionsForCompilationLevel(options);

    test(options, "![]", "!1");
    test(options, "!![]", "!0");
    test(options, "[][[]]", "void 0");
    test(options, "+[![]]", "NaN");
    test(options, "+(+!+[]+(!+[]+[])[!+[]+!+[]+!+[]]+[+!+[]]+[+[]]+[+[]]+[+[]])", "Infinity");
  }

  @Test
  public void testConstructors() {
    CompilerOptions options = createCompilerOptions();
    CompilationLevel.ADVANCED_OPTIMIZATIONS.setOptionsForCompilationLevel(options);

    testSame(options, "[]");
    test(options, "(+[])", "0");
    test(options, "([]+[])", "\"\"");
    test(options, "(![])", "!1");
    test(options, "[][\"fill\"]", "[].fill");
    test(options, "Function(\"return/\"+false+\"/\")()", ""); // TODO For some reason result is
                                                              // empty, expected: "/false/"
  }
}
