// @generated
// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: cd/resources/proto/javacd.proto

package com.facebook.buck.cd.model.java;

@javax.annotation.Generated(value="protoc", comments="annotations:LibraryJarBaseCommandOrBuilder.java.pb.meta")
public interface LibraryJarBaseCommandOrBuilder extends
    // @@protoc_insertion_point(interface_extends:javacd.api.v1.LibraryJarBaseCommand)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <code>.RelPath pathToClasses = 3;</code>
   */
  boolean hasPathToClasses();
  /**
   * <code>.RelPath pathToClasses = 3;</code>
   */
  com.facebook.buck.cd.model.common.RelPath getPathToClasses();
  /**
   * <code>.RelPath pathToClasses = 3;</code>
   */
  com.facebook.buck.cd.model.common.RelPathOrBuilder getPathToClassesOrBuilder();

  /**
   * <code>.RelPath rootOutput = 4;</code>
   */
  boolean hasRootOutput();
  /**
   * <code>.RelPath rootOutput = 4;</code>
   */
  com.facebook.buck.cd.model.common.RelPath getRootOutput();
  /**
   * <code>.RelPath rootOutput = 4;</code>
   */
  com.facebook.buck.cd.model.common.RelPathOrBuilder getRootOutputOrBuilder();

  /**
   * <code>.RelPath pathToClassHashes = 5;</code>
   */
  boolean hasPathToClassHashes();
  /**
   * <code>.RelPath pathToClassHashes = 5;</code>
   */
  com.facebook.buck.cd.model.common.RelPath getPathToClassHashes();
  /**
   * <code>.RelPath pathToClassHashes = 5;</code>
   */
  com.facebook.buck.cd.model.common.RelPathOrBuilder getPathToClassHashesOrBuilder();

  /**
   * <code>.RelPath annotationsPath = 6;</code>
   */
  boolean hasAnnotationsPath();
  /**
   * <code>.RelPath annotationsPath = 6;</code>
   */
  com.facebook.buck.cd.model.common.RelPath getAnnotationsPath();
  /**
   * <code>.RelPath annotationsPath = 6;</code>
   */
  com.facebook.buck.cd.model.common.RelPathOrBuilder getAnnotationsPathOrBuilder();

  /**
   * <code>.javacd.api.v1.UnusedDependenciesParams unusedDependenciesParams = 7;</code>
   */
  boolean hasUnusedDependenciesParams();
  /**
   * <code>.javacd.api.v1.UnusedDependenciesParams unusedDependenciesParams = 7;</code>
   */
  com.facebook.buck.cd.model.java.UnusedDependenciesParams getUnusedDependenciesParams();
  /**
   * <code>.javacd.api.v1.UnusedDependenciesParams unusedDependenciesParams = 7;</code>
   */
  com.facebook.buck.cd.model.java.UnusedDependenciesParamsOrBuilder getUnusedDependenciesParamsOrBuilder();
}