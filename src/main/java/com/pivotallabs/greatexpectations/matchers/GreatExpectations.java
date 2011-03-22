package com.pivotallabs.greatexpectations.matchers;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;

import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;

import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ALOAD;
import static org.objectweb.asm.Opcodes.INVOKESPECIAL;
import static org.objectweb.asm.Opcodes.INVOKESTATIC;
import static org.objectweb.asm.Opcodes.RETURN;
import static org.objectweb.asm.Opcodes.V1_5;

public class GreatExpectations {

  public static RuntimeException lastExpectTrace = null;
  private static final String WRAPPER_SUFFIX = "$$wrapper";
  private static ClassLoader wrappingClassLoader;

  static {
    wrappingClassLoader = new WrappingClassLoader(GreatExpectations.class.getClassLoader());
  }

  @SuppressWarnings({"UnusedDeclaration"})
  public static boolean wrap(BaseMatcher baseMatcher, String methodName, boolean result, Object[] expectArgs) {
    if (result == baseMatcher.inverted) {
      StringBuilder message = new StringBuilder();
      message
          .append("Failure: Expected <")
          .append(baseMatcher.actual)
          .append(baseMatcher.inverted ? "> not " : "> ")
          .append(methodName.replaceAll("([A-Z])", " $1").toLowerCase());

      for (int i = 0; i < expectArgs.length; i++) {
        Object expectArg = expectArgs[i];
        message.append(i == 0 ? " <" : ">, <");

        if (expectArg instanceof Object[]) {
          expectArg = Arrays.asList((Object[]) expectArg);
        }
        message.append(expectArg);
      }
      if (expectArgs.length > 0) message.append(">");
      throw new AssertionError(message.toString());
    }
    return true;
  }

  @SuppressWarnings({"UnusedDeclaration"})
  public static void resetTrace() {
    GreatExpectations.lastExpectTrace = null;
  }

  public static void checkForUnfinishedExpect() {
    if (lastExpectTrace != null) {
      RuntimeException e = lastExpectTrace;
      lastExpectTrace = null;
      throw e;
    }
  }

  public static <T, M extends BaseMatcher<T, M>> M wrapped(Class<M> matcherClass, T actual) {
    GreatExpectations.checkForUnfinishedExpect();
    GreatExpectations.lastExpectTrace = new RuntimeException("you called expect() without a matcher!");

    Class<M> wrappedMatcherClass = loadMatcher(matcherClass);
    try {
      M matcher = wrappedMatcherClass.newInstance();
      matcher.actual = actual;

      matcher.not = wrappedMatcherClass.newInstance();
      matcher.not.inverted = true;
      matcher.not.actual = actual;

      return matcher;
    } catch (InstantiationException e) {
      throw new RuntimeException(e);
    } catch (IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }

  private static <M extends BaseMatcher> Class<M> loadMatcher(Class<M> matcherClass) {
    try {
      return (Class<M>) wrappingClassLoader.loadClass(matcherClass.getName() + WRAPPER_SUFFIX);
    } catch (ClassNotFoundException e) {
      throw new RuntimeException(e);
    }
  }

  private static class WrappingClassLoader extends ClassLoader {
    protected WrappingClassLoader(ClassLoader classLoader) {
      super(classLoader);
    }

    @Override
    protected Class<?> findClass(String className) throws ClassNotFoundException {
      if (className.endsWith(WRAPPER_SUFFIX)) {
        Class<?> parentClass = loadClass(className.substring(0, className.length() - WRAPPER_SUFFIX.length()));

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cw.visit(V1_5, ACC_PUBLIC,
            classRef(className), null, classRef(parentClass),
            new String[]{});

        MethodVisitor constructor = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(ALOAD, 0);
        constructor.visitMethodInsn(INVOKESPECIAL, classRef(parentClass), "<init>", "()V");
        constructor.visitInsn(RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();

        for (Method method : parentClass.getMethods()) {
          if (method.getDeclaringClass().getName().equals(Object.class.getName()))
            continue;

          if (!method.getReturnType().equals(Boolean.TYPE)) {
            throw new IllegalArgumentException("wrong return type for " + method);
          }

          MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, method.getName(),
              Type.getMethodDescriptor(method), null, null);
          GeneratorAdapter generatorAdapter = new GeneratorAdapter(mv,
              ACC_PUBLIC, method.getName(), Type.getMethodDescriptor(method));

          generatorAdapter.visitCode();

          generatorAdapter.invokeStatic(Type.getType(GreatExpectations.class),
              new org.objectweb.asm.commons.Method("resetTrace", Type.VOID_TYPE, new Type[0]));

          generatorAdapter.loadThis(); // wrap arg 0
          generatorAdapter.push(method.getName()); // wrap arg 1

          generatorAdapter.visitVarInsn(ALOAD, 0); // super this
          generatorAdapter.loadArgs(); // super args
          mv.visitMethodInsn(INVOKESPECIAL,
              classRef(method.getDeclaringClass()), method.getName(),
              Type.getMethodDescriptor(method)); // invoke super, wrap arg 2

          generatorAdapter.loadArgArray(); // wrap arg 3

          generatorAdapter.visitMethodInsn(INVOKESTATIC, Type.getInternalName(GreatExpectations.class),
              "wrap", "(Lcom/pivotallabs/greatexpectations/matchers/BaseMatcher;Ljava/lang/String;Z[Ljava/lang/Object;)Z");
          generatorAdapter.returnValue();
          generatorAdapter.endMethod();
        }

        cw.visitEnd();
        byte[] b = cw.toByteArray();

        try {
          new DataOutputStream(new FileOutputStream("/tmp/" + className + "ASM.class")).write(b);
        } catch (IOException e) {
          throw new RuntimeException(e);
        }

        return defineClass(className, b, 0, b.length);
      } else {
        return super.findClass(className);
      }
    }

    private String classRef(String className) {
      return className.replace('.', '/');
    }

    private String classRef(Class clazz) {
      return Type.getInternalName(clazz);
    }
  }
}
