/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.python.codeInsight.typing;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Ref;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiPolyVariantReference;
import com.intellij.psi.util.CachedValueProvider;
import com.intellij.psi.util.CachedValuesManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.HashSet;
import com.jetbrains.python.PyCustomType;
import com.jetbrains.python.PyNames;
import com.jetbrains.python.codeInsight.functionTypeComments.psi.PyFunctionTypeAnnotation;
import com.jetbrains.python.codeInsight.functionTypeComments.psi.PyFunctionTypeAnnotationFile;
import com.jetbrains.python.codeInsight.functionTypeComments.psi.PyParameterTypeList;
import com.jetbrains.python.psi.*;
import com.jetbrains.python.psi.impl.PyExpressionCodeFragmentImpl;
import com.jetbrains.python.psi.resolve.PyResolveContext;
import com.jetbrains.python.psi.types.*;
import one.util.streamex.StreamEx;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.jetbrains.python.psi.PyUtil.as;

/**
 * @author vlan
 */
public class PyTypingTypeProvider extends PyTypeProviderBase {
  public static final String GENERATOR = "typing.Generator";
  public static final String ASYNC_GENERATOR = "typing.AsyncGenerator";
  public static final String COROUTINE = "typing.Coroutine";

  public static final Pattern TYPE_COMMENT_PATTERN = Pattern.compile("# *type: *(.*)");

  private static final ImmutableMap<String, String> COLLECTION_CLASSES = ImmutableMap.<String, String>builder()
    .put("typing.List", "list")
    .put("typing.Dict", "dict")
    .put("typing.Set", PyNames.SET)
    .put("typing.FrozenSet", "frozenset")
    .put("typing.Tuple", PyNames.TUPLE)
    .build();

  public static final ImmutableMap<String, String> TYPING_COLLECTION_CLASSES = ImmutableMap.<String, String>builder()
    .put("list", "List")
    .put("dict", "Dict")
    .put("set", "Set")
    .put("frozenset", "FrozenSet")
    .build();

  private static final ImmutableSet<String> GENERIC_CLASSES = ImmutableSet.<String>builder()
    .add("typing.Generic")
    .add("typing.AbstractGeneric")
    .add("typing.Protocol")
    .build();

  private static final ImmutableSet<String> OPAQUE_NAMES = ImmutableSet.<String>builder()
    .add("typing.overload")
    .add("typing.Any")
    .add("typing.TypeVar")
    .add("typing.Generic")
    .add("typing.Tuple")
    .add("typing.Callable")
    .add("typing.Type")
    .add("typing.no_type_check")
    .add("typing.Union")
    .add("typing.Optional")
    .add("typing.List")
    .add("typing.Dict")
    .add("typing.DefaultDict")
    .add("typing.Set")
    .build();

  @Nullable
  @Override
  public PyType getReferenceExpressionType(@NotNull PyReferenceExpression referenceExpression, @NotNull TypeEvalContext context) {
    // Check for the exact name in advance for performance reasons
    if ("Generic".equals(referenceExpression.getName())) {
      if (resolveToQualifiedNames(referenceExpression, context).contains("typing.Generic")) {
        return createTypingGenericType();
      }
    }
    return null;
  }

  @Nullable
  public Ref<PyType> getParameterType(@NotNull PyNamedParameter param, @NotNull PyFunction func, @NotNull TypeEvalContext context) {
    final Ref<PyType> typeFromAnnotation = getParameterTypeFromAnnotation(param, context);
    if (typeFromAnnotation != null) {
      return typeFromAnnotation;
    }

    final Ref<PyType> typeFromTypeComment = getParameterTypeFromTypeComment(param, context);
    if (typeFromTypeComment != null) {
      return typeFromTypeComment;
    }

    final PyFunctionTypeAnnotation annotation = getFunctionTypeAnnotation(func);
    if (annotation == null) {
      return null;
    }
    final PyParameterTypeList list = annotation.getParameterTypeList();
    final List<PyExpression> params = list.getParameterTypes();
    if (params.size() == 1) {
      final PyNoneLiteralExpression noneExpr = as(params.get(0), PyNoneLiteralExpression.class);
      if (noneExpr != null && noneExpr.isEllipsis()) {
        return Ref.create();
      }
    }
    final int startOffset = omitFirstParamInTypeComment(func) ? 1 : 0;
    final List<PyParameter> funcParams = Arrays.asList(func.getParameterList().getParameters());
    final int i = funcParams.indexOf(param) - startOffset;
    if (i >= 0 && i < params.size()) {
      return Ref.create(getParameterTypeFromFunctionComment(params.get(i), context));
    }
    return null;
  }

  @Nullable
  private static PyType getParameterTypeFromFunctionComment(@NotNull PyExpression expression, @NotNull TypeEvalContext context) {
    final PyStarExpression starExpr = as(expression, PyStarExpression.class);
    if (starExpr != null) {
      final PyExpression inner = starExpr.getExpression();
      if (inner != null) {
        return PyTypeUtil.toPositionalContainerType(expression, getType(inner, new Context(context)));
      }
    }
    final PyDoubleStarExpression doubleStarExpr = as(expression, PyDoubleStarExpression.class);
    if (doubleStarExpr != null) {
      final PyExpression inner = doubleStarExpr.getExpression();
      if (inner != null) {
        return PyTypeUtil.toKeywordContainerType(expression, getType(inner, new Context(context)));
      }
    }
    return getType(expression, new Context(context));
  }

  @Nullable
  private static Ref<PyType> getParameterTypeFromTypeComment(@NotNull PyNamedParameter parameter, @NotNull TypeEvalContext context) {
    final String typeComment = parameter.getTypeCommentAnnotation();

    if (typeComment != null) {
      final PyType type = getStringBasedType(typeComment, parameter, new Context(context));

      if (parameter.isPositionalContainer()) {
        return Ref.create(PyTypeUtil.toPositionalContainerType(parameter, type));
      }

      if (parameter.isKeywordContainer()) {
        return Ref.create(PyTypeUtil.toKeywordContainerType(parameter, type));
      }

      return Ref.create(type);
    }

    return null;
  }

  @Nullable
  private static Ref<PyType> getParameterTypeFromAnnotation(@NotNull PyNamedParameter parameter, @NotNull TypeEvalContext context) {
    final PyType annotationValueType = Optional
      .ofNullable(parameter.getAnnotation())
      .map(PyAnnotation::getValue) // XXX: Requires switching from stub to AST
      .map(value -> getType(value, new Context(context)))
      .orElse(null);

    if (annotationValueType != null) {
      if (parameter.isPositionalContainer()) {
        return Ref.create(PyTypeUtil.toPositionalContainerType(parameter, annotationValueType));
      }

      if (parameter.isKeywordContainer()) {
        return Ref.create(PyTypeUtil.toKeywordContainerType(parameter, annotationValueType));
      }

      final PyType result = Optional
        .ofNullable(parameter.getDefaultValue())
        .map(context::getType)
        .filter(PyNoneType.class::isInstance)
        .map(noneType -> PyUnionType.union(annotationValueType, noneType))
        .orElse(annotationValueType);

      return Ref.create(result);
    }

    return null;
  }

  @NotNull
  private static PyType createTypingGenericType() {
    return new PyCustomType("typing.Generic", null, false);
  }

  private static boolean omitFirstParamInTypeComment(@NotNull PyFunction func) {
    return func.getContainingClass() != null && func.getModifier() != PyFunction.Modifier.STATICMETHOD;
  }

  @Nullable
  @Override
  public Ref<PyType> getReturnType(@NotNull PyCallable callable, @NotNull TypeEvalContext context) {
    if (callable instanceof PyFunction) {
      final PyFunction function = (PyFunction)callable;
      // We model generic classes as return types of their constructors here
      final boolean isInit = PyUtil.isInit(function);
      if (isInit) {
        final PyClass cls = function.getContainingClass();
        if (cls != null) {
          final PyType genericType = getGenericType(cls, context);
          if (genericType != null) {
            return Ref.create(genericType);
          }
        }
      }
      final PyExpression value = getReturnTypeAnnotation(function);
      if (value != null) {
        final PyType type = getType(value, new Context(context));
        if (isInit && type instanceof PyNoneType) {
          return null;
        }
        return type != null ? Ref.create(type) : null;
      }
    }
    return null;
  }

  @Nullable
  private static PyExpression getReturnTypeAnnotation(@NotNull PyFunction function) {
    final PyAnnotation annotation = function.getAnnotation();
    if (annotation != null) {
      // XXX: Requires switching from stub to AST
      return annotation.getValue();
    }
    final PyFunctionTypeAnnotation functionAnnotation = getFunctionTypeAnnotation(function);
    if (functionAnnotation != null) {
      return functionAnnotation.getReturnType();
    }
    return null;
  }

  @Nullable
  private static PyFunctionTypeAnnotation getFunctionTypeAnnotation(@NotNull PyFunction function) {
    final String comment = function.getTypeCommentAnnotation();
    if (comment == null) {
      return null;
    }
    final PyFunctionTypeAnnotationFile file = CachedValuesManager.getCachedValue(function, () ->
      CachedValueProvider.Result.create(new PyFunctionTypeAnnotationFile(comment, function), function));
    return file.getAnnotation();
  }

  @Nullable
  @Override
  public Ref<PyType> getCallType(@NotNull PyFunction function, @Nullable PyCallSiteExpression callSite, @NotNull TypeEvalContext context) {
    if ("typing.cast".equals(function.getQualifiedName())) {
      return Optional
        .ofNullable(as(callSite, PyCallExpression.class))
        .map(PyCallExpression::getArguments)
        .filter(args -> args.length > 0)
        .map(args -> getType(args[0], new Context(context)))
        .map(Ref::create)
        .orElse(null);
    }

    return null;
  }

  @Override
  public PyType getReferenceType(@NotNull PsiElement referenceTarget, TypeEvalContext context, @Nullable PsiElement anchor) {
    if (referenceTarget instanceof PyTargetExpression) {
      final PyTargetExpression target = (PyTargetExpression)referenceTarget;
      // Depends on typing.Generic defined as a target expression
      if ("typing.Generic".equals(target.getQualifiedName())) {
        return createTypingGenericType();
      }
      if (context.maySwitchToAST(target)) {
        // XXX: Requires switching from stub to AST
        final PyAnnotation annotation = target.getAnnotation();
        if (annotation != null) {
          final PyExpression value = annotation.getValue();
          if (value != null) {
            return getType(value, new Context(context));
          }
          return null;
        }
      }
      final String comment = target.getTypeCommentAnnotation();
      if (comment != null) {
        final PyType type = getStringBasedType(comment, referenceTarget, new Context(context));
        if (type instanceof PyTupleType) {
          final PyTupleExpression tupleExpr = PsiTreeUtil.getParentOfType(target, PyTupleExpression.class);
          if (tupleExpr != null) {
            return PyTypeChecker.getTargetTypeFromTupleAssignment(target, tupleExpr, (PyTupleType)type);
          }
        }
        return type;
      }
    }
    return null;
  }

  /**
   * Checks that text of a comment starts with the "type:" prefix and returns trimmed part afterwards. This trailing part is supposed to
   * contain type annotation in PEP 484 compatible format.
   */
  @Nullable
  public static String getTypeCommentValue(@NotNull String text) {
    final Matcher m = TYPE_COMMENT_PATTERN.matcher(text);
    if (m.matches()) {
      return m.group(1);
    }
    return null;
  }

  @Nullable
  @Override
  public PyType getGenericType(@NotNull PyClass cls, @NotNull TypeEvalContext context) {
    final List<PyType> genericTypes = collectGenericTypes(cls, new Context(context));
    if (genericTypes.isEmpty()) {
      return null;
    }
    return new PyCollectionTypeImpl(cls, false, genericTypes);
  }

  private static boolean isAny(@NotNull PyType type) {
    return type instanceof PyClassType && "typing.Any".equals(((PyClassType)type).getPyClass().getQualifiedName());
  }

  @NotNull
  private static List<PyType> collectGenericTypes(@NotNull PyClass cls, @NotNull Context context) {
    boolean isGeneric = false;
    for (PyClassLikeType ancestor : cls.getAncestorTypes(context.getTypeContext())) {
      if (ancestor != null && GENERIC_CLASSES.contains(ancestor.getClassQName())) {
        isGeneric = true;
        break;
      }
    }
    if (isGeneric) {
      // XXX: Requires switching from stub to AST
      for (PyExpression expr : cls.getSuperClassExpressions()) {
        if (expr instanceof PySubscriptionExpression) {
          final PyExpression indexExpr = ((PySubscriptionExpression)expr).getIndexExpression();
          final PyTupleExpression tupleExpr = as(indexExpr, PyTupleExpression.class);
          final List<PyExpression> generics = tupleExpr != null ?
                                              Arrays.asList(tupleExpr.getElements()) : Collections.singletonList(indexExpr);
          return StreamEx.of(generics)
            .flatMap(e -> tryResolving(e, context.getTypeContext()).stream())
            .map(e -> as(getGenericTypeFromTypeVar(e, context), PyType.class))
            .nonNull()
            .toList();
        }
      }
    }
    return Collections.emptyList();
  }

  @Nullable
  private static PyType getType(@NotNull PyExpression expression, @NotNull Context context) {
    final List<PyType> members = Lists.newArrayList();
    for (PsiElement resolved : tryResolving(expression, context.getTypeContext())) {
      members.add(getTypeForResolvedElement(resolved, context));
    }
    return PyUnionType.union(members);
  }

  @Nullable
  private static PyType getTypeForResolvedElement(@NotNull PsiElement resolved, @NotNull Context context) {
    if (context.getExpressionCache().contains(resolved)) {
      // Recursive types are not yet supported
      return null;
    }

    context.getExpressionCache().add(resolved);
    try {
      final PyType unionType = getUnionType(resolved, context);
      if (unionType != null) {
        return unionType;
      }
      final Ref<PyType> optionalType = getOptionalType(resolved, context);
      if (optionalType != null) {
        return optionalType.get();
      }
      final PyType callableType = getCallableType(resolved, context);
      if (callableType != null) {
        return callableType;
      }
      final PyType parameterizedType = getParameterizedType(resolved, context);
      if (parameterizedType != null) {
        return parameterizedType;
      }
      final PyType builtinCollection = getBuiltinCollection(resolved);
      if (builtinCollection != null) {
        return builtinCollection;
      }
      final PyType genericType = getGenericTypeFromTypeVar(resolved, context);
      if (genericType != null) {
        return genericType;
      }
      final Ref<PyType> classType = getClassType(resolved, context.getTypeContext());
      if (classType != null) {
        return classType.get();
      }
      final PyType stringBasedType = getStringBasedType(resolved, context);
      if (stringBasedType != null) {
        return stringBasedType;
      }
      return null;
    }
    finally {
      context.getExpressionCache().remove(resolved);
    }
  }

  @Nullable
  private static Ref<PyType> getClassType(@NotNull PsiElement element, @NotNull TypeEvalContext context) {
    if (element instanceof PyTypedElement) {
      final PyType type = context.getType((PyTypedElement)element);
      if (type != null && isAny(type)) {
        return Ref.create();
      }
      if (type instanceof PyClassLikeType) {
        final PyClassLikeType classType = (PyClassLikeType)type;
        if (classType.isDefinition()) {
          final PyType instanceType = classType.toInstance();
          return Ref.create(instanceType);
        }
      }
      else if (type instanceof PyNoneType) {
        return Ref.create(type);
      }
    }
    return null;
  }

  @Nullable
  private static Ref<PyType> getOptionalType(@NotNull PsiElement element, @NotNull Context context) {
    if (element instanceof PySubscriptionExpression) {
      final PySubscriptionExpression subscriptionExpr = (PySubscriptionExpression)element;
      final PyExpression operand = subscriptionExpr.getOperand();
      final Collection<String> operandNames = resolveToQualifiedNames(operand, context.getTypeContext());
      if (operandNames.contains("typing.Optional")) {
        final PyExpression indexExpr = subscriptionExpr.getIndexExpression();
        if (indexExpr != null) {
          final PyType type = getType(indexExpr, context);
          if (type != null) {
            return Ref.create(PyUnionType.union(type, PyNoneType.INSTANCE));
          }
        }
        return Ref.create();
      }
    }
    return null;
  }

  @Nullable
  private static PyType getStringBasedType(@NotNull PsiElement element, @NotNull Context context) {
    if (element instanceof PyStringLiteralExpression) {
      // XXX: Requires switching from stub to AST
      final String contents = ((PyStringLiteralExpression)element).getStringValue();
      return getStringBasedType(contents, element, context);
    }
    return null;
  }

  @Nullable
  private static PyType getStringBasedType(@NotNull String contents, @NotNull PsiElement anchor, @NotNull Context context) {
    final Project project = anchor.getProject();
    final PyExpressionCodeFragmentImpl codeFragment = new PyExpressionCodeFragmentImpl(project, "dummy.py", contents, false);
    codeFragment.setContext(anchor.getContainingFile());
    final PsiElement element = codeFragment.getFirstChild();
    if (element instanceof PyExpressionStatement) {
      final PyExpression expr = ((PyExpressionStatement)element).getExpression();
      if (expr instanceof PyTupleExpression) {
        final PyTupleExpression tupleExpr = (PyTupleExpression)expr;
        final List<PyType> elementTypes = ContainerUtil.map(tupleExpr.getElements(), elementExpr -> getType(elementExpr, context));
        return PyTupleType.create(anchor, elementTypes);
      }
      return getType(expr, context);
    }
    return null;
  }

  @Nullable
  private static PyType getCallableType(@NotNull PsiElement resolved, @NotNull Context context) {
    if (resolved instanceof PySubscriptionExpression) {
      final PySubscriptionExpression subscriptionExpr = (PySubscriptionExpression)resolved;
      final PyExpression operand = subscriptionExpr.getOperand();
      final Collection<String> operandNames = resolveToQualifiedNames(operand, context.getTypeContext());
      if (operandNames.contains("typing.Callable")) {
        final PyExpression indexExpr = subscriptionExpr.getIndexExpression();
        if (indexExpr instanceof PyTupleExpression) {
          final PyTupleExpression tupleExpr = (PyTupleExpression)indexExpr;
          final PyExpression[] elements = tupleExpr.getElements();
          if (elements.length == 2) {
            final PyExpression parametersExpr = elements[0];
            final PyExpression returnTypeExpr = elements[1];
            if (parametersExpr instanceof PyListLiteralExpression) {
              final List<PyCallableParameter> parameters = new ArrayList<>();
              final PyListLiteralExpression listExpr = (PyListLiteralExpression)parametersExpr;
              for (PyExpression argExpr : listExpr.getElements()) {
                parameters.add(new PyCallableParameterImpl(null, getType(argExpr, context)));
              }
              final PyType returnType = getType(returnTypeExpr, context);
              return new PyCallableTypeImpl(parameters, returnType);
            }
            if (isEllipsis(parametersExpr)) {
              return new PyCallableTypeImpl(null, getType(returnTypeExpr, context));
            }
          }
        }
      }
    }
    return null;
  }

  private static boolean isEllipsis(@NotNull PyExpression parametersExpr) {
    return parametersExpr instanceof PyNoneLiteralExpression && ((PyNoneLiteralExpression)parametersExpr).isEllipsis();
  }

  @Nullable
  private static PyType getUnionType(@NotNull PsiElement element, @NotNull Context context) {
    if (element instanceof PySubscriptionExpression) {
      final PySubscriptionExpression subscriptionExpr = (PySubscriptionExpression)element;
      final PyExpression operand = subscriptionExpr.getOperand();
      final Collection<String> operandNames = resolveToQualifiedNames(operand, context.getTypeContext());
      if (operandNames.contains("typing.Union")) {
        return PyUnionType.union(getIndexTypes(subscriptionExpr, context));
      }
    }
    return null;
  }

  @Nullable
  private static PyGenericType getGenericTypeFromTypeVar(@NotNull PsiElement element, @NotNull Context context) {
    if (element instanceof PyCallExpression) {
      final PyCallExpression assignedCall = (PyCallExpression)element;
      final PyExpression callee = assignedCall.getCallee();
      if (callee != null) {
        final Collection<String> calleeQNames = resolveToQualifiedNames(callee, context.getTypeContext());
        if (calleeQNames.contains("typing.TypeVar")) {
          final PyExpression[] arguments = assignedCall.getArguments();
          if (arguments.length > 0) {
            final PyExpression firstArgument = arguments[0];
            if (firstArgument instanceof PyStringLiteralExpression) {
              final String name = ((PyStringLiteralExpression)firstArgument).getStringValue();
              if (name != null) {
                return new PyGenericType(name, getGenericTypeBound(arguments, context));
              }
            }
          }
        }
      }
    }
    return null;
  }

  @Nullable
  private static PyType getGenericTypeBound(@NotNull PyExpression[] typeVarArguments, @NotNull Context context) {
    final List<PyType> types = new ArrayList<>();
    for (int i = 1; i < typeVarArguments.length; i++) {
      types.add(getType(typeVarArguments[i], context));
    }
    return PyUnionType.union(types);
  }

  @NotNull
  private static List<PyType> getIndexTypes(@NotNull PySubscriptionExpression expression, @NotNull Context context) {
    final List<PyType> types = new ArrayList<>();
    final PyExpression indexExpr = expression.getIndexExpression();
    if (indexExpr instanceof PyTupleExpression) {
      final PyTupleExpression tupleExpr = (PyTupleExpression)indexExpr;
      for (PyExpression expr : tupleExpr.getElements()) {
        types.add(getType(expr, context));
      }
    }
    else if (indexExpr != null) {
      types.add(getType(indexExpr, context));
    }
    return types;
  }

  @Nullable
  private static PyType getParameterizedType(@NotNull PsiElement element, @NotNull Context context) {
    if (element instanceof PySubscriptionExpression) {
      final PySubscriptionExpression subscriptionExpr = (PySubscriptionExpression)element;
      final PyExpression operand = subscriptionExpr.getOperand();
      final PyExpression indexExpr = subscriptionExpr.getIndexExpression();
      final PyType operandType = getType(operand, context);
      if (operandType instanceof PyClassType) {
        final PyClass cls = ((PyClassType)operandType).getPyClass();
        final List<PyType> indexTypes = getIndexTypes(subscriptionExpr, context);
        if (PyNames.TUPLE.equals(cls.getQualifiedName())) {
          if (indexExpr instanceof PyTupleExpression) {
            final PyExpression[] elements = ((PyTupleExpression)indexExpr).getElements();
            if (elements.length == 2 && isEllipsis(elements[1])) {
              return PyTupleType.createHomogeneous(element, indexTypes.get(0));
            }
          }
          return PyTupleType.create(element, indexTypes);
        }
        else if (indexExpr != null) {
          return new PyCollectionTypeImpl(cls, false, indexTypes);
        }
      }
    }
    return null;
  }

  @Nullable
  private static PyType getBuiltinCollection(@NotNull PsiElement element) {
    final String collectionName = getQualifiedName(element);
    final String builtinName = COLLECTION_CLASSES.get(collectionName);
    return builtinName != null ? PyTypeParser.getTypeByName(element, builtinName) : null;
  }

  @NotNull
  private static List<PsiElement> tryResolving(@NotNull PyExpression expression, @NotNull TypeEvalContext context) {
    final List<PsiElement> elements = Lists.newArrayList();
    if (expression instanceof PyReferenceExpression) {
      final PyReferenceExpression referenceExpr = (PyReferenceExpression)expression;
      final PyResolveContext resolveContext = PyResolveContext.noImplicits().withTypeEvalContext(context);
      final PsiPolyVariantReference reference = referenceExpr.getReference(resolveContext);
      final List<PsiElement> resolved = PyUtil.multiResolveTopPriority(reference);
      for (PsiElement element : resolved) {
        if (element instanceof PyFunction) {
          final PyFunction function = (PyFunction)element;
          if (PyUtil.isInit(function)) {
            final PyClass cls = function.getContainingClass();
            if (cls != null) {
              elements.add(cls);
              continue;
            }
          }
        }
        final String name = element != null ? getQualifiedName(element) : null;
        if (name != null && OPAQUE_NAMES.contains(name)) {
          elements.add(element);
          continue;
        }
        if (element instanceof PyTargetExpression) {
          final PyTargetExpression targetExpr = (PyTargetExpression)element;
          // XXX: Requires switching from stub to AST
          final PyExpression assignedValue = targetExpr.findAssignedValue();
          if (assignedValue != null) {
            elements.add(assignedValue);
            continue;
          }
        }
        if (element != null) {
          elements.add(element);
        }
      }
    }
    return !elements.isEmpty() ? elements : Collections.singletonList(expression);
  }

  @NotNull
  private static Collection<String> resolveToQualifiedNames(@NotNull PyExpression expression, @NotNull TypeEvalContext context) {
    final Set<String> names = Sets.newLinkedHashSet();
    for (PsiElement resolved : tryResolving(expression, context)) {
      final String name = getQualifiedName(resolved);
      if (name != null) {
        names.add(name);
      }
    }
    return names;
  }

  @Nullable
  private static String getQualifiedName(@NotNull PsiElement element) {
    if (element instanceof PyQualifiedNameOwner) {
      final PyQualifiedNameOwner qualifiedNameOwner = (PyQualifiedNameOwner)element;
      return qualifiedNameOwner.getQualifiedName();
    }
    return null;
  }

  private static class Context {
    @NotNull private final TypeEvalContext myContext;
    @NotNull private final Set<PsiElement> myCache = new HashSet<>();

    private Context(@NotNull TypeEvalContext context) {
      myContext = context;
    }

    @NotNull
    public TypeEvalContext getTypeContext() {
      return myContext;
    }
    
    @NotNull
    public Set<PsiElement> getExpressionCache() {
      return myCache;
    }
  }
}
