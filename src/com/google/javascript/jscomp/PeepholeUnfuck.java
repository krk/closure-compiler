/*
 * Copyright 2019 The Closure Compiler Authors.
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

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.TokenStream;

class PeepholeUnfuck extends AbstractPeepholeOptimization {

  private static class Pair<X, Y> {
    public final X x;
    public final Y y;

    public Pair(X x, Y y) {
      this.x = x;
      this.y = y;
    }
  }
  private static class Tuple3<X, Y, Z> {
    public final X x;
    public final Y y;
    public final Z z;

    public Tuple3(X x, Y y, Z z) {
      this.x = x;
      this.y = y;
      this.z = z;
    }
  }

  private static final SimpleDateFormat jsDateFormat =
      new SimpleDateFormat("EEE MMM dd yyyy '00:00:00' 'GMT'Z '('z')'");

  // Only constant characters shall be referenced, e.g. "G".
  private static final String dateNow =
      "Mon Jan 01 2001 00:00:00 GMT+0100 (Central European Standard Time)";
  private static final DecimalFormat doubleIntFormat = new DecimalFormat("#.##############");

  private static final Set<String> arrayFunctions = new HashSet<String>(
      Arrays.asList("concat", "copyWithin", "entries", "every", "fill", "filter", "find",
          "findIndex", "flat", "flatMap", "forEach", "includes", "indexOf", "join", "keys",
          "lastIndexOf", "map", "pop", "push", "reduce", "reduceRight", "reverse", "shift", "slice",
          "some", "sort", "splice", "toLocaleString", "toSource", "toString", "unshift", "values"));

  private static final Set<String> evalArrayFunctions =
      new HashSet<String>(Arrays.asList("fill", "filter", "sort"));

  private static final Set<String> constructors = new HashSet<String>(
      Arrays.asList("Array", "Number", "String", "Boolean", "Function", "RegExp"));

  // Only applies to empty strings, as in `"".blink()`.
  private static final Map<String, Pair<String, String>> stringToHtml =
      new HashMap<String, Pair<String, String>>() {
        {
          put("big", new Pair<String, String>("<big>", "</big>"));
          put("blink", new Pair<String, String>("<blink>", "</blink>"));
          put("bold", new Pair<String, String>("<b>", "</b>"));
          put("fixed", new Pair<String, String>("<tt>", "</tt>"));
          put("italics", new Pair<String, String>("<i>", "</i>"));
          put("small", new Pair<String, String>("<small>", "</small>"));
          put("strike", new Pair<String, String>("<strike>", "</strike>"));
          put("sub", new Pair<String, String>("<sub>", "</sub>"));
          put("sup", new Pair<String, String>("<sup>", "</sup>"));
        }
      };

  private static final Map<String, Tuple3<String, String, String>> stringToHtmlWithParam =
      new HashMap<String, Tuple3<String, String, String>>() {
        {
          put("anchor", new Tuple3<String, String, String>("<a name=\"", "\">", "</a>"));
          put("fontcolor", new Tuple3<String, String, String>("<font color=\"", "\">", "</font>"));
          put("fontsize", new Tuple3<String, String, String>("<font size=\"", "\">", "</font>"));
          put("link", new Tuple3<String, String, String>("<a href=\"", "\">", "</a>"));
        }
      };

  PeepholeUnfuck() {}

  private static final Boolean logUnfuckers = false;

  private void printIf(Boolean condition, String log) {
    if (!condition) {
      return;
    }
    System.out.println(log);
  }

  /**
   * Tries to apply our various peephole unfuckifications on the passed in node.
   */
  @Override
  public Node optimizeSubtree(Node n) {
    Node node = tryUndefined(n);
    if (node != n) {
      printIf(logUnfuckers, "1");
      return node;
    }

    node = tryStringIndexedString(n);
    if (node != n) {
      printIf(logUnfuckers, "2");
      return node;
    }

    node = tryArrayLiteralFunctionStringCoercion(n);
    if (node != n) {
      printIf(logUnfuckers, "3");
      return node;
    }

    node = tryArrayFunctionConstructorInvocation(n);
    if (node != n) {
      printIf(logUnfuckers, "4");
      return node;
    }

    node = tryFunctionConstructorInvocation(n);
    if (node != n) {
      printIf(logUnfuckers, "5");
      return node;
    }

    node = tryUnfuckArrayEntries(n);
    if (node != n) {
      printIf(logUnfuckers, "6");
      return node;
    }

    node = tryConstructorCoercion(n);
    if (node != n) {
      printIf(logUnfuckers, "7");
      return node;
    }

    node = tryConstructorNameCoercion(n);
    if (node != n) {
      printIf(logUnfuckers, "8");
      return node;
    }

    node = tryEvaluateIntToStringBase(n);
    if (node != n) {
      printIf(logUnfuckers, "8");
      return node;
    }

    node = tryEvaluateEscapes(n);
    if (node != n) {
      printIf(logUnfuckers, "9");
      return node;
    }

    node = tryEvaluateStringToHtmlElement(n);
    if (node != n) {
      printIf(logUnfuckers, "10");
      return node;
    }

    node = tryEvaluateStringSlice(n);
    if (node != n) {
      printIf(logUnfuckers, "11");
      return node;
    }

    node = tryEvaluateDate(n);
    if (node != n) {
      printIf(logUnfuckers, "12");
      return node;
    }

    node = tryEvaluateNewDate(n);
    if (node != n) {
      printIf(logUnfuckers, "13");
      return node;
    }

    node = tryEvaluateGetConstructor(n);
    if (node != n) {
      printIf(logUnfuckers, "14");
      return node;
    }

    node = tryEvaluateEval(n);
    if (node != n) {
      printIf(logUnfuckers, "15");
      return node;
    }

    node = tryCoerceNaNObjectLit(n);
    if (node != n) {
      printIf(logUnfuckers, "16");
      return node;
    }

    node = tryCoerceNaNObjectLitCall(n);
    if (node != n) {
      printIf(logUnfuckers, "17");
      return node;
    }

    node = tryEvaluateArraySliceOfString(n);
    if (node != n) {
      printIf(logUnfuckers, "18");
      return node;
    }

    node = tryCoerceRegExpObject(n);
    if (node != n) {
      printIf(logUnfuckers, "19");
      return node;
    }

    node = tryCoerceWindowObject(n);
    if (node != n) {
      printIf(logUnfuckers, "20");
      return node;
    }

    node = tryEvaluateFromCharCode(n);
    if (node != n) {
      printIf(logUnfuckers, "21");
      return node;
    }

    return n;
  }

  private Node tryEvaluateEval(Node n) {
    if (!n.isCall() || !n.hasTwoChildren()) {
      return n;
    }

    // Eval only if parent is add, getelem or getprop, not to break
    // CommandLineRunnerTest.testIssue81.
    Node parent = n.getParent();
    if (parent == null || !(parent.isAdd() || isGetPropOrElem(parent))) {
      return n;
    }

    Node name = n.getFirstChild();
    if (!name.isName() || name.getString() != "eval") {
      return n;
    }

    Node str = n.getLastChild();
    if (!str.isString()) {
      return n;
    }

    Node replacement = tryEval(str.getString(), str, n);
    if (replacement == n) {
      return n;
    }

    n.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private Node tryEvaluateFromCharCode(Node n) {
    // ""["constructor"]["fromCharCode"]("0")
    if (!n.isCall() || !n.hasTwoChildren()) {
      return n;
    }

    Node getProp1 = n.getFirstChild();
    if (!isGetPropOrElem(getProp1) || !getProp1.hasTwoChildren()) {
      return n;
    }
    Node getProp2 = getProp1.getFirstChild();
    if (!isGetPropOrElem(getProp2) || !getProp2.hasTwoChildren()) {
      return n;
    }
    Node left = getProp2.getFirstChild();
    if (!left.isString()) {
      return n;
    }
    Node right = getProp2.getLastChild();
    if (!right.isString() || right.getString() != "constructor") {
      return n;
    }

    Node funcName = getProp1.getLastChild();
    if (!funcName.isString() || funcName.getString() != "fromCharCode") {
      return n;
    }

    Optional<Integer> code = tryGetInteger(n.getLastChild());
    if (!code.isPresent()) {
      return n;
    }

    int c = code.get();
    if (c < 0 || c > 65535) {
      return n;
    }

    Node replacement = IR.string(Character.toString((char) c));
    n.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private Node tryCoerceWindowObject(Node n) {
    if (!n.isAdd() || !n.hasTwoChildren()) {
      return n;
    }

    String js = new CodePrinter.Builder(n).build();
    Node left = n.getFirstChild();
    if (!left.isName() || left.hasChildren() || left.getString() != "this") {
      return n;
    }

    Node right = n.getLastChild();
    if (!((right.isString() && right.getString() == "")
        || (right.isArrayLit() && !right.hasChildren()))) {
      return n;
    }

    Node replacement = IR.string("[object Window]");
    n.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private Node tryCoerceRegExpObject(Node n) {
    if (!n.isAdd() || !n.hasTwoChildren()) {
      return n;
    }

    Node left = n.getFirstChild();
    if (!left.isCall() || !left.hasOneChild()) {
      return n;
    }
    Node re = left.getFirstChild();
    if (!re.isName() || re.hasChildren() || re.getString() != "RegExp") {
      return n;
    }

    Node right = n.getLastChild();
    if (!right.isString() || right.getString() != "") {
      return n;
    }

    Node replacement = IR.string("/(?:)/");
    n.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private Node getObjectConstructor(Node n) {
    Node newLeft = null;
    if (!isGetPropOrElem(n) || !n.hasTwoChildren()) {
      return null;
    }

    Node ctor = n.getLastChild();
    if (ctor.isString() && ctor.getString() == "constructor") {
      Node obj = n.getFirstChild();
      switch (obj.getToken()) {
        case STRING:
          newLeft = IR.name("String");
          break;
        case NUMBER:
          newLeft = IR.name("Number");
          break;
        case REGEXP:
          newLeft = IR.name("RegExp");
          break;
        default:
          break;
      }
    }

    return newLeft;
  }

  private Node tryEvaluateGetConstructor(Node n) {
    if (!n.isAdd() || !n.hasTwoChildren()) {
      return n;
    }
    Node left = n.getFirstChild();
    Node right = n.getLastChild();
    if (!isGetPropOrElem(left) && !isGetPropOrElem(right)) {
      return n;
    }

    Node newLeft = getObjectConstructor(left);
    Node newRight = getObjectConstructor(right);

    if (newLeft == null && newRight == null) {
      return n;
    }
    if (newLeft == null) {
      left.detach();
      newLeft = left;
    }
    if (newRight == null) {
      right.detach();
      newRight = right;
    }
    Node replacement = IR.add(newLeft, newRight);
    n.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private Node tryEvaluateDate(Node n) {
    if (!n.isCall() || !n.hasOneChild()) {
      return n;
    }

    Node parent = n.getParent();
    if (parent == null || !parent.isAdd()) {
      return n;
    }

    Node name = n.getFirstChild();
    if (!name.isName() || name.getString() != "Date") {
      return n;
    }

    Node replacement = IR.string(dateNow);
    n.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private Node tryEvaluateNewDate(Node n) {
    if (!n.isAdd()) {
      return n;
    }

    Node str = n.getLastChild();
    String suffix;
    if (str.isString()) {
      suffix = str.getString();
    } else if (str.isArrayLit() && !str.hasChildren()) {
      suffix = "";
    } else {
      return n;
    }

    Node left = n.getFirstChild();
    if (!left.isNew() || !left.hasTwoChildren()) {
      return n;
    }

    Node date = left.getFirstChild();
    if (!date.isName() || date.getString() != "Date") {
      return n;
    }

    Optional<Double> opt = tryGetNumber(left.getLastChild());
    if (!opt.isPresent()) {
      return n;
    }

    long num = opt.get().longValue();

    Date d = new Date(num);
    String strDate = jsDateFormat.format(d);

    Node replacement = IR.string(strDate + suffix);
    n.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private Node tryEvaluateArraySliceOfString(Node n) {
    // [].slice.call("false") -> [ "f", "a", "l", "s", "e" ]
    if (!isGetPropOrElem(n) || !n.hasTwoChildren()) {
      return n;
    }

    Node parent = n.getParent();
    if (parent == null || !parent.isCall() || !parent.hasTwoChildren()) {
      return n;
    }

    Node arg = parent.getLastChild();
    if (!arg.isString()) {
      return n;
    }

    Node getProp = n.getFirstChild();
    if (!isGetPropOrElem(getProp) || !getProp.hasTwoChildren()) {
      return n;
    }
    Node left = getProp.getFirstChild();
    if (!left.isArrayLit() || left.hasChildren()) {
      return n;
    }
    Node right = getProp.getLastChild();
    if (!right.isString() || right.getString() != "slice") {
      return n;
    }

    Node call = n.getLastChild();
    if (!call.isString() || call.getString() != "call") {
      return n;
    }

    Node replacement = IR.arraylit();
    for (char c : arg.getString().toCharArray()) {
      replacement.addChildToFront(IR.string(Character.toString(c)));
    }

    parent.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private Node tryEvaluateStringSlice(Node n) {
    if (!n.isGetProp() || !n.hasTwoChildren()) {
      return n;
    }

    Node parent = n.getParent();
    if (parent == null || !parent.isCall()
        || !(parent.hasTwoChildren() || parent.getChildCount() == 3)) {
      return n;
    }

    Node right = n.getLastChild();
    if (!right.isString() || right.getString() != "slice") {
      return n;
    }

    Node left = n.getFirstChild();
    if (!left.isString()) {
      return n;
    }
    String subject = left.getString();

    Node arg = parent.getSecondChild();
    Optional<Integer> b = tryGetInteger(arg);
    if (!b.isPresent()) {
      return n;
    }

    int beginIndex = b.get();
    int endIndex;

    if (parent.hasTwoChildren()) {
      endIndex = subject.length();
    } else {
      Node endArg = parent.getLastChild();
      Optional<Integer> e = tryGetInteger(endArg);
      if (!e.isPresent()) {
        return n;
      }
      endIndex = e.get();
    }

    // https://tc39.github.io/ecma262/#sec-string.prototype.slice
    int len = subject.length();
    if (beginIndex < 0) {
      beginIndex = Math.max(len + beginIndex, 0);
    } else {
      beginIndex = Math.min(beginIndex, len);
    }

    if (endIndex < 0) {
      endIndex = Math.max(len + endIndex, 0);
    } else {
      endIndex = Math.min(endIndex, len);
    }

    int span = Math.max(endIndex - beginIndex, 0);
    String result = subject.substring(beginIndex, beginIndex + span);

    Node replacement = IR.string(result);
    parent.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private String getNativeDecl(String name) {
    return "function " + name + "() {\n    [native code]\n}";
  }

  private Node tryEvaluateStringToHtmlElement(Node n) {
    if (!isGetPropOrElem(n) || !n.hasTwoChildren()) {
      return n;
    }

    Node parent = n.getParent();
    if (parent == null || !parent.isCall() || !(parent.hasOneChild() || parent.hasTwoChildren())) {
      return n;
    }

    Node left = n.getFirstChild();
    if (!left.isString()) {
      return n;
    }

    Node right = n.getLastChild();
    if (!right.isString()) {
      return n;
    }

    Tuple3<String, String, String> affixes3 = null;
    Pair<String, String> affixes = stringToHtml.get(right.getString());
    if (affixes == null) {
      affixes3 = stringToHtmlWithParam.get(right.getString());
      if (affixes3 == null) {
        return n;
      }
    }

    String str = left.getString();

    String param;
    if (parent.hasOneChild()) {
      param = "";
    } else {
      Node arg = parent.getLastChild();
      if (!arg.isString()) {
        return n;
      }

      param = arg.getString();
    }

    Node replacement;
    if (affixes != null) {
      replacement = IR.string(affixes.x + str + affixes.y);
    } else {
      replacement = IR.string(affixes3.x + param + affixes3.y + str + affixes3.z);
    }
    parent.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private Node tryEvaluateEscapes(Node n) {
    if (!n.isCall() || !n.hasTwoChildren()) {
      return n;
    }

    Node name = n.getFirstChild();
    if (!name.isName()) {
      return n;
    }
    String nameStr = name.getString();
    if (!(nameStr == "escape" || nameStr == "unescape")) {
      return n;
    }
    Boolean isEscape = nameStr == "escape";

    String str;
    Node arg = n.getLastChild();
    if (arg.isString()) {
      str = arg.getString();
    } else if (arg.isGetProp() && arg.hasTwoChildren() && arg.getFirstChild().isArrayLit()
        && !arg.getFirstChild().hasChildren()) {
      Node second = arg.getLastChild();
      if (!second.isString()) {
        return n;
      }

      String funcName = second.getString();
      if (!PeepholeUnfuck.arrayFunctions.contains(funcName)) {
        return n;
      }
      str = getNativeDecl(funcName);
    } else {
      return n;
    }

    String result;
    try {
      if (isEscape) { // TODO Check if URLEncoder.encode is equivalent to
        // https://tc39.github.io/ecma262/#sec-escape-string
        result = URLEncoder.encode(str, "utf-8");
      } else {
        result = URLDecoder.decode(str, "utf-8");
      }
    } catch (UnsupportedEncodingException e) {
      return n;
    }

    Node replacement = IR.string(result);
    n.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private Node tryEvaluateIntToStringBase(Node n) {
    if (!n.isCall() || !n.hasTwoChildren()) {
      return n;
    }

    Node get = n.getFirstChild();
    if (!isGetPropOrElem(get) || !get.hasTwoChildren()) {
      return n;
    }
    Node num = get.getFirstChild();
    if (!num.isNumber()) {
      return n;
    }
    double d = num.getDouble();
    if (d < 0) {
      return n;
    }
    int i = (int) d;
    if (i != d) {
      return n;
    }
    Node op = get.getLastChild();
    if (!op.isString() || op.getString() != "toString") {
      return n;
    }

    Optional<Integer> base = tryGetInteger(n.getLastChild());
    if (!base.isPresent()) {
      return n;
    }
    int b = base.get();
    if (b < 2 || b > 36) {
      return n;
    }

    String result = Integer.toString(i, b);
    Node replacement = IR.string(result);
    n.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private Node tryConstructorNameCoercion(Node n) {
    if (!n.isGetProp()) {
      return n;
    }
    Node left = n.getFirstChild();
    if (!left.isName()) {
      return n;
    }
    String name = left.getString();
    if (!constructors.contains(name)) {
      return n;
    }
    Node right = n.getLastChild();
    if (!right.isString() || right.getString() != "name") {
      return n;
    }

    Node replacement = IR.string(name);
    n.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private Node coerceNaNToString(Node n) {
    if (n.isName() && n.getString() == "NaN") {
      return IR.string("NaN");
    }
    return n;
  }

  private Node tryCoerceNaNObjectLitCall(Node n) {
    if (!n.isAdd() || !n.hasTwoChildren()) {
      return n;
    }

    String prefix;
    Node prefixNode = n.getFirstChild();
    if (prefixNode.isName() && prefixNode.getString() == "NaN") {
      prefix = "NaN";
    } else if (prefixNode.isString()) {
      prefix = prefixNode.getString();
    } else {
      return n;
    }

    Node call = n.getLastChild();
    if (!call.isCall() || !call.hasOneChild()) {
      return n;
    }

    Node getProp1 = call.getFirstChild();
    if (!isGetPropOrElem(getProp1) || !getProp1.hasTwoChildren()) {
      return n;
    }

    Node getProp2 = getProp1.getFirstChild();
    if (!isGetPropOrElem(getProp2) || !getProp2.hasTwoChildren()) {
      return n;
    }

    Node objectLit = getProp2.getFirstChild();
    if (!objectLit.isObjectLit() || objectLit.hasChildren()) {
      return n;
    }
    Node toString = getProp2.getLastChild();
    if (!toString.isString() || toString.getString() != "toString") {
      return n;
    }

    Node callStr = getProp1.getLastChild();
    if (!callStr.isString() || callStr.getString() != "call") {
      return n;
    }

    Node replacement = IR.string(prefix + "[object Undefined]");
    n.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private Node tryCoerceNaNObjectLit(Node n) {
    if (!n.isAdd() || !n.hasTwoChildren()) {
      return n;
    }
    Node left = n.getFirstChild();
    if (!left.isName() || left.getString() != "NaN") {
      return n;
    }

    Node right = n.getLastChild();
    if (!right.isObjectLit() || right.hasChildren()) {
      return n;
    }

    Node replacement = IR.string("NaN[object Object]");
    n.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private Node tryConstructorCoercion(Node n) {
    if (!n.isAdd() || !n.hasTwoChildren()) {
      return n;
    }

    Node left = n.getFirstChild();
    Node right = n.getLastChild();
    if (!left.isName() && !right.isName()) {
      return n;
    }

    Node replacement = null;

    if (left.isName() && right.isName()) {
      String leftName = left.getString();
      String rightName = right.getString();
      if (constructors.contains(leftName) && constructors.contains(rightName)) {
        replacement = IR.string(getNativeDecl(leftName) + getNativeDecl(rightName));
      } else if (constructors.contains(leftName)) {
        right.detach();
        replacement = IR.add(IR.string(getNativeDecl(leftName)), coerceNaNToString(right));
      } else if (constructors.contains(rightName)) {
        left.detach();
        replacement = IR.add(coerceNaNToString(left), IR.string(getNativeDecl(rightName)));
      }
    } else if (left.isName()) {
      String leftName = left.getString();
      if (constructors.contains(leftName)) {
        right.detach();
        replacement = IR.add(IR.string(getNativeDecl(leftName)), coerceNaNToString(right));
      }
    } else if (right.isName()) {
      String rightName = right.getString();
      if (constructors.contains(rightName)) {
        left.detach();
        replacement = IR.add(coerceNaNToString(left), IR.string(getNativeDecl(rightName)));
      }
    }

    if (replacement == null) {
      return n;
    }

    n.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private Node tryUnfuckArrayEntries(Node n) {
    if (!n.isAdd() || !n.hasTwoChildren()) {
      return n;
    }

    Node call = n.getFirstChild();
    if (!call.isCall() || !call.hasOneChild()) {
      return n;
    }

    Node string = n.getLastChild();
    if (!string.isString()) {
      return n;
    }

    Node getProp = call.getFirstChild();
    if (!getProp.isGetProp() || !getProp.hasTwoChildren()) {
      return n;
    }
    if (!getProp.getFirstChild().isArrayLit() || getProp.getFirstChild().hasChildren()) {
      return n;
    }
    Node entries = getProp.getLastChild();
    if (!entries.isString() || entries.getString() != "entries") {
      return n;
    }

    String suffix = string.getString();
    Node replacement = IR.string("[object Array Iterator]" + suffix);
    n.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private Node tryFunctionConstructorInvocation(Node n) {
    if (!n.isCall() || !n.hasTwoChildren()) {
      return n;
    }

    Node parent = n.getParent();
    if (parent == null || !parent.isCall() || !parent.hasOneChild()) {
      return n;
    }

    Node name = n.getFirstChild();
    if (!name.isName() || name.getString() != "Function") {
      return n;
    }
    Node argNode = n.getLastChild();
    if (!argNode.isString()) {
      return n;
    }
    String arg = argNode.getString();
    if (!arg.startsWith("return")) {
      return n;
    }
    String code = arg.substring("return".length()).trim();

    Node replacement = tryEval(code, parent, argNode);
    parent.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private Node tryArrayFunctionConstructorInvocation(Node n) {
    if (!n.isCall() || !n.hasTwoChildren()) {
      return n;
    }

    Node parent = n.getParent();
    if (parent == null || !parent.isCall() || !parent.hasOneChild()) {
      return n;
    }

    Node get1 = n.getFirstChild();
    if (!isGetPropOrElem(get1) || !get1.hasTwoChildren()) {
      return n;
    }
    Node get2 = get1.getFirstChild();
    if (!isGetPropOrElem(get2) || !get2.hasTwoChildren()) {
      return n;
    }
    if (!get2.getFirstChild().isArrayLit()) {
      return n;
    }
    Node filter = get2.getLastChild();
    if (!filter.isString() || !evalArrayFunctions.contains(filter.getString())) {
      return n;
    }

    Node ctor = get1.getLastChild();
    if (!ctor.isString() || ctor.getString() != "constructor") {
      return n;
    }

    Node subject = n.getLastChild();
    if (!subject.isString()) {
      return n;
    }

    String arg = subject.getString();
    if (!arg.startsWith("return")) {
      return n;
    }
    String code = arg.substring("return".length()).trim();

    // We have `[].filter["constructor"]("XX")()`.
    Node replacement = tryEval(code, parent, subject);
    parent.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private Node tryArrayLiteralFunctionStringCoercion(Node n) {
    if (!n.isGetProp() || !n.hasTwoChildren()) {
      return n;
    }

    Node add = n.getParent();
    if (add == null || !add.isAdd()) {
      return n;
    }

    // First operand can be a boolean, string, number or an arraylit `[]`.
    String affix;

    Boolean isReversed = add.getFirstChild() == n;
    Node op = isReversed ? add.getSecondChild() : add.getFirstChild();
    switch (op.getToken()) {
      case TRUE:
        affix = "true";
        break;
      case FALSE:
        affix = "false";
        break;
      case STRING:
        affix = op.getString();
        break;
      case NUMBER:
        affix = PeepholeUnfuck.doubleIntFormat.format(op.getDouble());
        break;
      case ARRAYLIT:
        affix = "";
        break;
      case NAME:
        String name = op.getString();
        if (name == "NaN") {
          affix = "NaN";
          break;
        } else if (name == "undefined") {
          affix = "undefined";
          break;
        }
        return n;
      default:
        if (NodeUtil.isUndefined(op)) {
          affix = "undefined";
          break;
        }
        return n;
    }

    Node left = n.getFirstChild();
    if (!left.isArrayLit()) {
      return n;
    }

    Node right = n.getLastChild();
    if (!right.isString()) {
      return n;
    }

    String name = right.getString();
    if (!PeepholeUnfuck.arrayFunctions.contains(name)) {
      return n;
    }
    String decl = getNativeDecl(name);

    // We have `X + [].func`.
    String result = isReversed ? decl + affix : affix + decl;
    Node replacement = IR.string(result);
    add.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private Node tryStringIndexedString(Node n) {
    if (!n.isGetElem() || !n.hasTwoChildren()) {
      return n;
    }

    Node left = n.getFirstChild();
    if (!left.isString()) {
      return n;
    }
    String leftStr = left.getString();

    Node right = n.getLastChild();
    if (!right.isString()) {
      return n;
    }
    int index;
    try {
      index = Integer.parseInt(right.getString());
    } catch (NumberFormatException e) {
      return n;
    }
    if (index < 0 || index >= leftStr.length()) {
      return n;
    }

    // We have `"string"["integer"]`.
    String result = Character.toString(leftStr.charAt(index));
    Node replacement = IR.string(result);
    n.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private Node tryUndefined(Node n) {
    if (!n.isGetElem()) {
      return n;
    }

    if (!n.hasTwoChildren()) {
      return n;
    }

    Node left = n.getFirstChild();
    if (!left.isArrayLit()) {
      return n;
    }
    if (left.hasChildren()) {
      return n;
    }

    Node right = n.getLastChild();
    if (!right.isArrayLit() || right.hasChildren()) {
      return n;
    }
    if (right.hasChildren()) {
      return n;
    }

    // We have `[][[]]`.
    Node replacement = IR.name("undefined");
    n.replaceWith(replacement);
    reportChangeToEnclosingScope(replacement);
    return replacement;
  }

  private Node tryEval(String code, Node codeSourceInfo, Node callSourceInfo) {
    Node replacement;
    if (code == "{}") {
      return IR.objectlit();
    } else if (code.startsWith("/") && code.endsWith("/") && code.length() >= 2) {
      // Possibly regex.
      String regex = code.substring(1, code.length() - 1);
      Node regexArg = IR.string(regex);
      regexArg.useSourceInfoFrom(codeSourceInfo);
      return IR.regexp(regexArg);
    } else if (code.startsWith("\"") && code.endsWith("\"") && code.length() >= 2) {
      // Possibly string.
      String string = code.substring(1, code.length() - 1);
      return IR.string(string);
    } else if (TokenStream.isJSIdentifier(code)) {
      return IR.name(code);
    }

    if (code.startsWith("new Date(") && code.endsWith(")")) {
      String value = code.substring("new Date(".length(), code.length() - 1);
      Node arg = null;
      try {
        double n = Double.parseDouble(value);
        arg = IR.number(n);
      } catch (NumberFormatException e) {
        // NOOP.
      }

      if (value.startsWith("\"") && value.endsWith("\"") && value.length() > 2) {
        arg = IR.string(value.substring(1, value.length() - 1));
      }

      if (arg != null) {
        return IR.newNode(IR.name("Date"), arg);
      }
    }

    try {
      double n = Double.parseDouble(code);
      return IR.number(n);
    } catch (NumberFormatException e) {
      // NOOP.
    }

    Node eval = IR.name("eval");
    eval.putBooleanProp(Node.DIRECT_EVAL, true);
    eval.useSourceInfoFrom(callSourceInfo);

    Node evalArg = IR.string(code);
    evalArg.useSourceInfoFrom(codeSourceInfo);

    replacement = IR.call(eval, evalArg);
    replacement.putBooleanProp(Node.FREE_CALL, true);
    return replacement;
  }

  private Optional<Double> tryGetNumber(Node arg) {
    double d;
    if (arg.isNumber()) {
      d = arg.getDouble();
    } else if (arg.isString()) {
      String s = arg.getString();

      try {
        d = Double.parseDouble(s);
      } catch (NullPointerException e) {
        return Optional.empty();
      } catch (NumberFormatException e) {
        return Optional.empty();
      }
    } else {
      return Optional.empty();
    }

    return Optional.of(d);
  }

  private Optional<Integer> tryGetInteger(Node arg) {
    Optional<Double> n = tryGetNumber(arg);

    if (!n.isPresent()) {
      return Optional.empty();
    }

    double d = n.get();
    int i = (int) d;
    if (i != d) {
      return Optional.empty();
    }

    return Optional.of(i);
  }

  private Boolean isGetPropOrElem(Node n) {
    return n.isGetProp() || n.isGetElem();
  }
}
