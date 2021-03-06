package com.tencent.qcloud.qcloudxml.compiler;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.FieldSpec;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeSpec;
import com.tencent.qcloud.qcloudxml.annoation.XmlBean;
import com.tencent.qcloud.qcloudxml.compiler.utils.ElementUtil;
import com.tencent.qcloud.qcloudxml.compiler.utils.Util;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.PUBLIC;

/**
 * 生成具体的类代码TypeSpec
 */
public class CodeCreator {
    private static final String TAG = "CodeGenerator";

    /**
     * 生成XmlAdapter类
     *
     * @param classElement 标记为XmlBean的类
     * @return XmlAdapter类TypeSpec
     */
    public static TypeSpec generateCode(TypeElement classElement) throws ProcessingException {
        ParameterizedTypeName genericParamXmlAdapter =
                ParameterizedTypeName.get(ClassName.get("com.tencent.qcloud.qcloudxml.core", "IXmlAdapter"), ClassName.get(classElement));

        TypeSpec.Builder builder = TypeSpec.classBuilder(Util.getElementFullName(classElement) + "$$XmlAdapter")
                .addSuperinterface(genericParamXmlAdapter)
                .addField(generateFields(classElement))
                .addModifiers(PUBLIC)
                .addMethod(generateConstructor(classElement))
                .addMethod(generateFromXmlCode(classElement))
                .addMethod(generateToXmlCode(classElement));
        return builder.build();
    }

    private static String xmlBeanName(TypeElement classElement) {

        String name = classElement.getSimpleName().toString();
        XmlBean annotation = classElement.getAnnotation(XmlBean.class);
        if (annotation != null && !"".equals(annotation.name())) {
            name = annotation.name();
        }
        return name;
    }

    /**
     * 生成FromXml方法
     */
    private static MethodSpec generateFromXmlCode(TypeElement classElement) {
        MethodSpec.Builder methodSpec = MethodSpec.methodBuilder("fromXml")
                .returns(ClassName.get(classElement))
                .addModifiers(PUBLIC)
                .addAnnotation(Override.class)
                .addParameter(ClassName.bestGuess("org.xmlpull.v1.XmlPullParser"), "xmlPullParser")
                .addException(IOException.class)
                .addException(ClassName.bestGuess("org.xmlpull.v1.XmlPullParserException"))
                .addStatement(String.format("%s value = new %s()", ClassName.get(classElement), ClassName.get(classElement)))
                .addStatement("int eventType = xmlPullParser.getEventType()")
                .beginControlFlow("while (eventType != XmlPullParser.END_DOCUMENT)")
                .beginControlFlow("switch (eventType)")

                .beginControlFlow("case XmlPullParser.START_TAG:")
                .addStatement("String tagName = xmlPullParser.getName()")
                .addStatement(String.format("ChildElementBinder<%s> childElementBinder = childElementBinders.get(tagName)", ClassName.get(classElement)))
                .beginControlFlow("if (childElementBinder != null)")
                .addStatement("childElementBinder.fromXml(xmlPullParser, value)")
                // TODO: 2020/9/15 需要跳过，否则应该忽略的节点可能会进行解析 造成混乱
//                .beginControlFlow("else")
//                .addStatement("skip")
                .endControlFlow()
                .endControlFlow()
                .addStatement("break")

                .beginControlFlow("case XmlPullParser.END_TAG:")
                .beginControlFlow(String.format("if(\"%s\".equalsIgnoreCase(xmlPullParser.getName()))",
                        xmlBeanName(classElement)))
                .addStatement("return value")
                .endControlFlow()
                .endControlFlow()

                .endControlFlow()
                .addStatement("eventType = xmlPullParser.next()")
                .endControlFlow()
                .addStatement("return value");


        return methodSpec.build();
    }

    /**
     * 生成ToXml方法
     */
    public static MethodSpec generateToXmlCode(TypeElement classElement) {
        MethodSpec.Builder methodSpec = MethodSpec.methodBuilder("toXml")
                .addModifiers(PUBLIC)
                .addAnnotation(Override.class)
                .addParameter(ClassName.bestGuess("org.xmlpull.v1.XmlSerializer"), "xmlSerializer")
                .addParameter(ClassName.get(classElement), "value")
                .addException(IOException.class)
                .addException(ClassName.bestGuess("org.xmlpull.v1.XmlPullParserException"))
                .beginControlFlow("if (value == null)")
                .addStatement("return")
                .endControlFlow()
                .addStatement(String.format("xmlSerializer.startTag(\"\", \"%s\")", Util.getTypeElementName(classElement)))
                .addCode(generateToXmlCodeBlock(classElement))
                .addStatement(String.format("xmlSerializer.endTag(\"\", \"%s\")", Util.getTypeElementName(classElement)));
        return methodSpec.build();
    }

    /**
     * 生成classElement各成员toxml操作
     */
    private static CodeBlock generateToXmlCodeBlock(TypeElement classElement) {
        CodeBlock.Builder codeBlock = CodeBlock.builder();
        List<? extends Element> elementList = classElement.getEnclosedElements();
        if (elementList == null || elementList.size() == 0) return codeBlock.build();
        for (Element element : classElement.getEnclosedElements()) {
            if (element.getKind().isField()) {
                TypeXmlKind typeXmlKind = ElementUtil.getXmlKind(element);
                if (typeXmlKind.isBaseType()) {
                    codeBlock.addStatement(String.format("xmlSerializer.startTag(\"\", \"%s\")", Util.getElementName(element)))
                            .addStatement(String.format("xmlSerializer.text(String.valueOf(value.%s))", element.getSimpleName()))
                            .addStatement(String.format("xmlSerializer.endTag(\"\", \"%s\")", Util.getElementName(element)));
                } else if (typeXmlKind == TypeXmlKind.LIST) {
                    if(!Util.ignoreListNote(element)){
                        codeBlock.addStatement(String.format("xmlSerializer.startTag(\"\", \"%s\")", Util.getElementName(element)));
                    }
                    codeBlock.beginControlFlow(String.format("if (value.%s != null)", element.getSimpleName()))
                            .beginControlFlow(String.format("for (int i =0; i<value.%s.size(); i++)", element.getSimpleName()))
                            .addStatement(String.format("%s.toXml(xmlSerializer, value.%s.get(i))",
                                    ClassName.bestGuess("com.tencent.qcloud.qcloudxml.core.QCloudXml"), element.getSimpleName()))
                            .endControlFlow()
                            .endControlFlow();
                    if(!Util.ignoreListNote(element)){
                        codeBlock.addStatement(String.format("xmlSerializer.endTag(\"\", \"%s\")", Util.getElementName((element))));
                    }
                } else if (typeXmlKind == TypeXmlKind.NORMAL_BEAN) {
                    codeBlock.beginControlFlow(String.format("if (value.%s != null)", element.getSimpleName()))
                            .addStatement(String.format("%s.toXml(xmlSerializer, value.%s)",
                                    ClassName.bestGuess("com.tencent.qcloud.qcloudxml.core.QCloudXml"), element.getSimpleName()))
                            .endControlFlow();
                }
            }
        }
        return codeBlock.build();
    }

    /**
     * 生成XmlAdapter childElementBinders字段，用于保存成员的解析操作
     */
    private static FieldSpec generateFields(TypeElement classElement) {
        ParameterizedTypeName childElementBinder = ParameterizedTypeName.get(
                ClassName.get("com.tencent.qcloud.qcloudxml.core", "ChildElementBinder"), ClassName.get(classElement));
        ParameterizedTypeName childElementBinderMapField = ParameterizedTypeName.get(ClassName.get(java.util.HashMap.class),
                ClassName.get(String.class), childElementBinder);

        return FieldSpec.builder(childElementBinderMapField, "childElementBinders", PRIVATE)
                .initializer("new HashMap<>()")
                .build();
    }

    /**
     * 生成构造函数，用于给childElementBinders字段添加相关操作
     */
    private static MethodSpec generateConstructor(TypeElement classElement) throws ProcessingException {
        MethodSpec.Builder methodSpec = MethodSpec.constructorBuilder()
                .addModifiers(PUBLIC);

        List<? extends Element> elementList = classElement.getEnclosedElements();
        if (elementList == null || elementList.size() == 0) return methodSpec.build();

        for (Element element : classElement.getEnclosedElements()) {
            if (element.getKind().isField()) {
                TypeSpec typeSpec = generateChildElementBinder(classElement, element);
                methodSpec.addStatement("childElementBinders.put($S, $L)", Util.getElementName(element), typeSpec);
            }
        }

        return methodSpec.build();
    }

    /**
     * 生成具体的ChildElementBinder
     */
    private static TypeSpec generateChildElementBinder(TypeElement classElement, Element element) throws ProcessingException {
        CodeBlock.Builder codeBlock = CodeBlock.builder();
        TypeXmlKind typeXmlKind = ElementUtil.getXmlKind(element);
        if (typeXmlKind.isBaseType()) {
            codeBlock.addStatement("xmlPullParser.next()");
            codeBlock.addStatement(String.format(generateBaseTypeAssignment(typeXmlKind), element.getSimpleName()));
        } else if (typeXmlKind == TypeXmlKind.LIST) {
            TypeMirror genericListType = Util.getGenericTypeFromList(element);
            ParameterizedTypeName valueTypeAsArrayList = ParameterizedTypeName.get(ClassName.get(ArrayList.class), ClassName.get(genericListType));
            codeBlock.beginControlFlow(String.format("if (value.%s == null)", element.getSimpleName()))
                    .addStatement(String.format("value.%s = new %s()", element.getSimpleName(), valueTypeAsArrayList))
                    .endControlFlow()

                    .addStatement("int eventType = xmlPullParser.getEventType()")
                    .beginControlFlow("while (eventType != XmlPullParser.END_DOCUMENT)")
                    .beginControlFlow("switch (eventType)")

                    .beginControlFlow("case XmlPullParser.START_TAG:")
                    .addStatement(String.format("value.%s.add(%s.fromXml(xmlPullParser, %s.class))",
                            element.getSimpleName(), ClassName.bestGuess("com.tencent.qcloud.qcloudxml.core.QCloudXml"), ClassName.get(genericListType)))
                    .endControlFlow()
                    .addStatement("break")

                    .beginControlFlow("case XmlPullParser.END_TAG:")
                    .beginControlFlow(String.format("if(\"%s\".equalsIgnoreCase(xmlPullParser.getName()))", Util.getElementName(element)))
                    .addStatement("return")
                    .endControlFlow()
                    .endControlFlow()

                    .endControlFlow()
                    .addStatement("eventType = xmlPullParser.next()")
                    .endControlFlow();
        } else if (typeXmlKind == TypeXmlKind.NORMAL_BEAN) {
            codeBlock.addStatement(String.format("value.%s = %s.fromXml(xmlPullParser, %s.class)",
                    element.getSimpleName(), ClassName.bestGuess("com.tencent.qcloud.qcloudxml.core.QCloudXml"), element.asType()));
        }

        MethodSpec fromXmlMethod = MethodSpec.methodBuilder("fromXml")
                .addAnnotation(Override.class)
                .addModifiers(Modifier.PUBLIC)
                .addParameter(ClassName.bestGuess("org.xmlpull.v1.XmlPullParser"), "xmlPullParser")
                .addParameter(ClassName.get(classElement), "value")
                .addException(IOException.class)
                .addException(ClassName.bestGuess("org.xmlpull.v1.XmlPullParserException"))
                .addCode(codeBlock.build())
                .build();

        ParameterizedTypeName childElementBinder = ParameterizedTypeName.get(
                ClassName.get("com.tencent.qcloud.qcloudxml.core", "ChildElementBinder"), ClassName.get(classElement));
        return TypeSpec.anonymousClassBuilder("")
                .addSuperinterface(childElementBinder)
                .addMethod(fromXmlMethod)
                .build();
    }

    /**
     * 生成基础类型赋值语句
     */
    private static String generateBaseTypeAssignment(TypeXmlKind typeXmlKind) {
        switch (typeXmlKind) {
            case STRING:
            case CHAR:
                return "value.%s = xmlPullParser.getText()";
            case INT:
                return "value.%s = Integer.parseInt(xmlPullParser.getText())";
            case BYTE:
                return "value.%s = Byte.parseByte(xmlPullParser.getText())";
            case LONG:
                return "value.%s = Long.parseLong(xmlPullParser.getText())";
            case FLOAT:
                return "value.%s = Float.parseFloat(xmlPullParser.getText())";
            case SHORT:
                return "value.%s = Short.parseShort(xmlPullParser.getText())";
            case DOUBLE:
                return "value.%s = Double.parseDouble(xmlPullParser.getText())";
            case BOOLEAN:
                return "value.%s = Boolean.parseBoolean(xmlPullParser.getText())";
            default:
                return "";
        }
    }
}
