// @generated
// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: cd/resources/proto/javacd.proto

package com.facebook.buck.cd.model.java;

@javax.annotation.Generated(value="protoc", comments="annotations:JarParametersOrBuilder.java.pb.meta")
public interface JarParametersOrBuilder extends
    // @@protoc_insertion_point(interface_extends:javacd.api.v1.JarParameters)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <code>bool hashEntries = 1;</code>
   */
  boolean getHashEntries();

  /**
   * <code>bool mergeManifests = 2;</code>
   */
  boolean getMergeManifests();

  /**
   * <code>.RelPath jarPath = 3;</code>
   */
  boolean hasJarPath();
  /**
   * <code>.RelPath jarPath = 3;</code>
   */
  com.facebook.buck.cd.model.common.RelPath getJarPath();
  /**
   * <code>.RelPath jarPath = 3;</code>
   */
  com.facebook.buck.cd.model.common.RelPathOrBuilder getJarPathOrBuilder();

  /**
   * <code>.javacd.api.v1.JarParameters.RemoveClassesPatternsMatcher removeEntryPredicate = 4;</code>
   */
  boolean hasRemoveEntryPredicate();
  /**
   * <code>.javacd.api.v1.JarParameters.RemoveClassesPatternsMatcher removeEntryPredicate = 4;</code>
   */
  com.facebook.buck.cd.model.java.JarParameters.RemoveClassesPatternsMatcher getRemoveEntryPredicate();
  /**
   * <code>.javacd.api.v1.JarParameters.RemoveClassesPatternsMatcher removeEntryPredicate = 4;</code>
   */
  com.facebook.buck.cd.model.java.JarParameters.RemoveClassesPatternsMatcherOrBuilder getRemoveEntryPredicateOrBuilder();

  /**
   * <code>repeated .RelPath entriesToJar = 5;</code>
   */
  java.util.List<com.facebook.buck.cd.model.common.RelPath> 
      getEntriesToJarList();
  /**
   * <code>repeated .RelPath entriesToJar = 5;</code>
   */
  com.facebook.buck.cd.model.common.RelPath getEntriesToJar(int index);
  /**
   * <code>repeated .RelPath entriesToJar = 5;</code>
   */
  int getEntriesToJarCount();
  /**
   * <code>repeated .RelPath entriesToJar = 5;</code>
   */
  java.util.List<? extends com.facebook.buck.cd.model.common.RelPathOrBuilder> 
      getEntriesToJarOrBuilderList();
  /**
   * <code>repeated .RelPath entriesToJar = 5;</code>
   */
  com.facebook.buck.cd.model.common.RelPathOrBuilder getEntriesToJarOrBuilder(
      int index);

  /**
   * <code>repeated .RelPath overrideEntriesToJar = 6;</code>
   */
  java.util.List<com.facebook.buck.cd.model.common.RelPath> 
      getOverrideEntriesToJarList();
  /**
   * <code>repeated .RelPath overrideEntriesToJar = 6;</code>
   */
  com.facebook.buck.cd.model.common.RelPath getOverrideEntriesToJar(int index);
  /**
   * <code>repeated .RelPath overrideEntriesToJar = 6;</code>
   */
  int getOverrideEntriesToJarCount();
  /**
   * <code>repeated .RelPath overrideEntriesToJar = 6;</code>
   */
  java.util.List<? extends com.facebook.buck.cd.model.common.RelPathOrBuilder> 
      getOverrideEntriesToJarOrBuilderList();
  /**
   * <code>repeated .RelPath overrideEntriesToJar = 6;</code>
   */
  com.facebook.buck.cd.model.common.RelPathOrBuilder getOverrideEntriesToJarOrBuilder(
      int index);

  /**
   * <code>string mainClass = 7;</code>
   */
  java.lang.String getMainClass();
  /**
   * <code>string mainClass = 7;</code>
   */
  com.google.protobuf.ByteString
      getMainClassBytes();

  /**
   * <code>.RelPath manifestFile = 8;</code>
   */
  boolean hasManifestFile();
  /**
   * <code>.RelPath manifestFile = 8;</code>
   */
  com.facebook.buck.cd.model.common.RelPath getManifestFile();
  /**
   * <code>.RelPath manifestFile = 8;</code>
   */
  com.facebook.buck.cd.model.common.RelPathOrBuilder getManifestFileOrBuilder();

  /**
   * <code>.javacd.api.v1.JarParameters.LogLevel duplicatesLogLevel = 9;</code>
   */
  int getDuplicatesLogLevelValue();
  /**
   * <code>.javacd.api.v1.JarParameters.LogLevel duplicatesLogLevel = 9;</code>
   */
  com.facebook.buck.cd.model.java.JarParameters.LogLevel getDuplicatesLogLevel();
}
