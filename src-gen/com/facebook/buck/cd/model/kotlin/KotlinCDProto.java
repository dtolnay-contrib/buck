// @generated
// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: cd/resources/proto/kotlincd.proto

package com.facebook.buck.cd.model.kotlin;

@javax.annotation.Generated(value="protoc", comments="annotations:KotlinCDProto.java.pb.meta")
public final class KotlinCDProto {
  private KotlinCDProto() {}
  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistryLite registry) {
  }

  public static void registerAllExtensions(
      com.google.protobuf.ExtensionRegistry registry) {
    registerAllExtensions(
        (com.google.protobuf.ExtensionRegistryLite) registry);
  }
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_kotlincd_api_v1_BuildKotlinCommand_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_kotlincd_api_v1_BuildKotlinCommand_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_kotlincd_api_v1_BaseCommandParams_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_kotlincd_api_v1_BaseCommandParams_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_kotlincd_api_v1_LibraryJarCommand_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_kotlincd_api_v1_LibraryJarCommand_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_kotlincd_api_v1_AbiJarCommand_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_kotlincd_api_v1_AbiJarCommand_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_kotlincd_api_v1_KotlinExtraParams_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_kotlincd_api_v1_KotlinExtraParams_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_kotlincd_api_v1_KotlinExtraParams_KotlinCompilerPluginsEntry_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_kotlincd_api_v1_KotlinExtraParams_KotlinCompilerPluginsEntry_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_kotlincd_api_v1_KotlinExtraParams_KosabiPluginOptionsEntry_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_kotlincd_api_v1_KotlinExtraParams_KosabiPluginOptionsEntry_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_kotlincd_api_v1_PluginParams_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_kotlincd_api_v1_PluginParams_fieldAccessorTable;
  static final com.google.protobuf.Descriptors.Descriptor
    internal_static_kotlincd_api_v1_PluginParams_ParamsEntry_descriptor;
  static final 
    com.google.protobuf.GeneratedMessageV3.FieldAccessorTable
      internal_static_kotlincd_api_v1_PluginParams_ParamsEntry_fieldAccessorTable;

  public static com.google.protobuf.Descriptors.FileDescriptor
      getDescriptor() {
    return descriptor;
  }
  private static  com.google.protobuf.Descriptors.FileDescriptor
      descriptor;
  static {
    java.lang.String[] descriptorData = {
      "\n!cd/resources/proto/kotlincd.proto\022\017kot" +
      "lincd.api.v1\032\037cd/resources/proto/common." +
      "proto\032\037cd/resources/proto/javacd.proto\"\330" +
      "\001\n\022BuildKotlinCommand\022=\n\021baseCommandPara" +
      "ms\030\001 \001(\0132\".kotlincd.api.v1.BaseCommandPa" +
      "rams\022?\n\021libraryJarCommand\030\002 \001(\0132\".kotlin" +
      "cd.api.v1.LibraryJarCommandH\000\0227\n\rabiJarC" +
      "ommand\030\003 \001(\0132\036.kotlincd.api.v1.AbiJarCom" +
      "mandH\000B\t\n\007command\"M\n\021BaseCommandParams\022\037" +
      "\n\027hasAnnotationProcessing\030\001 \001(\010\022\027\n\017withD" +
      "ownwardApi\030\002 \001(\010\"\316\001\n\021LibraryJarCommand\022=" +
      "\n\021kotlinExtraParams\030\001 \001(\0132\".kotlincd.api" +
      ".v1.KotlinExtraParams\0225\n\016baseJarCommand\030" +
      "\002 \001(\0132\035.javacd.api.v1.BaseJarCommand\022C\n\025" +
      "libraryJarBaseCommand\030\003 \001(\0132$.javacd.api" +
      ".v1.LibraryJarBaseCommand\"\275\001\n\rAbiJarComm" +
      "and\022=\n\021kotlinExtraParams\030\001 \001(\0132\".kotlinc" +
      "d.api.v1.KotlinExtraParams\0225\n\016baseJarCom" +
      "mand\030\002 \001(\0132\035.javacd.api.v1.BaseJarComman" +
      "d\0226\n\020abiJarParameters\030\003 \001(\0132\034.javacd.api" +
      ".v1.JarParameters\"\317\006\n\021KotlinExtraParams\022" +
      "\037\n\rpathToKotlinc\030\001 \001(\0132\010.AbsPath\022!\n\017extr" +
      "aClassPaths\030\002 \003(\0132\010.AbsPath\022*\n\030standardL" +
      "ibraryClassPath\030\003 \001(\0132\010.AbsPath\022/\n\035annot" +
      "ationProcessingClassPath\030\004 \001(\0132\010.AbsPath" +
      "\022K\n\030annotationProcessingTool\030\005 \001(\0162).kot" +
      "lincd.api.v1.AnnotationProcessingTool\022\035\n" +
      "\025extraKotlincArguments\030\006 \003(\t\022\\\n\025kotlinCo" +
      "mpilerPlugins\030\007 \003(\0132=.kotlincd.api.v1.Ko" +
      "tlinExtraParams.KotlinCompilerPluginsEnt" +
      "ry\022X\n\023kosabiPluginOptions\030\010 \003(\0132;.kotlin" +
      "cd.api.v1.KotlinExtraParams.KosabiPlugin" +
      "OptionsEntry\022\035\n\013friendPaths\030\t \003(\0132\010.AbsP" +
      "ath\022%\n\023kotlinHomeLibraries\030\n \003(\0132\010.AbsPa" +
      "th\022\021\n\tjvmTarget\030\013 \001(\t\022/\n\'shouldGenerateA" +
      "nnotationProcessingStats\030\014 \001(\010\022\032\n\022should" +
      "UseJvmAbiGen\030\r \001(\010\022,\n$shouldVerifySource" +
      "OnlyAbiConstraints\030\016 \001(\010\032[\n\032KotlinCompil" +
      "erPluginsEntry\022\013\n\003key\030\001 \001(\t\022,\n\005value\030\002 \001" +
      "(\0132\035.kotlincd.api.v1.PluginParams:\0028\001\032D\n" +
      "\030KosabiPluginOptionsEntry\022\013\n\003key\030\001 \001(\t\022\027" +
      "\n\005value\030\002 \001(\0132\010.AbsPath:\0028\001\"x\n\014PluginPar" +
      "ams\0229\n\006params\030\001 \003(\0132).kotlincd.api.v1.Pl" +
      "uginParams.ParamsEntry\032-\n\013ParamsEntry\022\013\n" +
      "\003key\030\001 \001(\t\022\r\n\005value\030\002 \001(\t:\0028\001*/\n\030Annotat" +
      "ionProcessingTool\022\010\n\004KAPT\020\000\022\t\n\005JAVAC\020\001B4" +
      "\n!com.facebook.buck.cd.model.kotlinB\rKot" +
      "linCDProtoP\001b\006proto3"
    };
    com.google.protobuf.Descriptors.FileDescriptor.InternalDescriptorAssigner assigner =
        new com.google.protobuf.Descriptors.FileDescriptor.    InternalDescriptorAssigner() {
          public com.google.protobuf.ExtensionRegistry assignDescriptors(
              com.google.protobuf.Descriptors.FileDescriptor root) {
            descriptor = root;
            return null;
          }
        };
    com.google.protobuf.Descriptors.FileDescriptor
      .internalBuildGeneratedFileFrom(descriptorData,
        new com.google.protobuf.Descriptors.FileDescriptor[] {
          com.facebook.buck.cd.model.common.CommonCDProto.getDescriptor(),
          com.facebook.buck.cd.model.java.JavaCDProto.getDescriptor(),
        }, assigner);
    internal_static_kotlincd_api_v1_BuildKotlinCommand_descriptor =
      getDescriptor().getMessageTypes().get(0);
    internal_static_kotlincd_api_v1_BuildKotlinCommand_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_kotlincd_api_v1_BuildKotlinCommand_descriptor,
        new java.lang.String[] { "BaseCommandParams", "LibraryJarCommand", "AbiJarCommand", "Command", });
    internal_static_kotlincd_api_v1_BaseCommandParams_descriptor =
      getDescriptor().getMessageTypes().get(1);
    internal_static_kotlincd_api_v1_BaseCommandParams_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_kotlincd_api_v1_BaseCommandParams_descriptor,
        new java.lang.String[] { "HasAnnotationProcessing", "WithDownwardApi", });
    internal_static_kotlincd_api_v1_LibraryJarCommand_descriptor =
      getDescriptor().getMessageTypes().get(2);
    internal_static_kotlincd_api_v1_LibraryJarCommand_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_kotlincd_api_v1_LibraryJarCommand_descriptor,
        new java.lang.String[] { "KotlinExtraParams", "BaseJarCommand", "LibraryJarBaseCommand", });
    internal_static_kotlincd_api_v1_AbiJarCommand_descriptor =
      getDescriptor().getMessageTypes().get(3);
    internal_static_kotlincd_api_v1_AbiJarCommand_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_kotlincd_api_v1_AbiJarCommand_descriptor,
        new java.lang.String[] { "KotlinExtraParams", "BaseJarCommand", "AbiJarParameters", });
    internal_static_kotlincd_api_v1_KotlinExtraParams_descriptor =
      getDescriptor().getMessageTypes().get(4);
    internal_static_kotlincd_api_v1_KotlinExtraParams_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_kotlincd_api_v1_KotlinExtraParams_descriptor,
        new java.lang.String[] { "PathToKotlinc", "ExtraClassPaths", "StandardLibraryClassPath", "AnnotationProcessingClassPath", "AnnotationProcessingTool", "ExtraKotlincArguments", "KotlinCompilerPlugins", "KosabiPluginOptions", "FriendPaths", "KotlinHomeLibraries", "JvmTarget", "ShouldGenerateAnnotationProcessingStats", "ShouldUseJvmAbiGen", "ShouldVerifySourceOnlyAbiConstraints", });
    internal_static_kotlincd_api_v1_KotlinExtraParams_KotlinCompilerPluginsEntry_descriptor =
      internal_static_kotlincd_api_v1_KotlinExtraParams_descriptor.getNestedTypes().get(0);
    internal_static_kotlincd_api_v1_KotlinExtraParams_KotlinCompilerPluginsEntry_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_kotlincd_api_v1_KotlinExtraParams_KotlinCompilerPluginsEntry_descriptor,
        new java.lang.String[] { "Key", "Value", });
    internal_static_kotlincd_api_v1_KotlinExtraParams_KosabiPluginOptionsEntry_descriptor =
      internal_static_kotlincd_api_v1_KotlinExtraParams_descriptor.getNestedTypes().get(1);
    internal_static_kotlincd_api_v1_KotlinExtraParams_KosabiPluginOptionsEntry_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_kotlincd_api_v1_KotlinExtraParams_KosabiPluginOptionsEntry_descriptor,
        new java.lang.String[] { "Key", "Value", });
    internal_static_kotlincd_api_v1_PluginParams_descriptor =
      getDescriptor().getMessageTypes().get(5);
    internal_static_kotlincd_api_v1_PluginParams_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_kotlincd_api_v1_PluginParams_descriptor,
        new java.lang.String[] { "Params", });
    internal_static_kotlincd_api_v1_PluginParams_ParamsEntry_descriptor =
      internal_static_kotlincd_api_v1_PluginParams_descriptor.getNestedTypes().get(0);
    internal_static_kotlincd_api_v1_PluginParams_ParamsEntry_fieldAccessorTable = new
      com.google.protobuf.GeneratedMessageV3.FieldAccessorTable(
        internal_static_kotlincd_api_v1_PluginParams_ParamsEntry_descriptor,
        new java.lang.String[] { "Key", "Value", });
    com.facebook.buck.cd.model.common.CommonCDProto.getDescriptor();
    com.facebook.buck.cd.model.java.JavaCDProto.getDescriptor();
  }

  // @@protoc_insertion_point(outer_class_scope)
}
