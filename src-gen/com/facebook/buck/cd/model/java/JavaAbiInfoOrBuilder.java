// @generated
// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: cd/resources/proto/javacd.proto

package com.facebook.buck.cd.model.java;

@javax.annotation.Generated(value="protoc", comments="annotations:JavaAbiInfoOrBuilder.java.pb.meta")
public interface JavaAbiInfoOrBuilder extends
    // @@protoc_insertion_point(interface_extends:javacd.api.v1.JavaAbiInfo)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <code>string buildTargetName = 1;</code>
   */
  java.lang.String getBuildTargetName();
  /**
   * <code>string buildTargetName = 1;</code>
   */
  com.google.protobuf.ByteString
      getBuildTargetNameBytes();

  /**
   * <code>repeated .Path contentPaths = 2;</code>
   */
  java.util.List<com.facebook.buck.cd.model.common.Path> 
      getContentPathsList();
  /**
   * <code>repeated .Path contentPaths = 2;</code>
   */
  com.facebook.buck.cd.model.common.Path getContentPaths(int index);
  /**
   * <code>repeated .Path contentPaths = 2;</code>
   */
  int getContentPathsCount();
  /**
   * <code>repeated .Path contentPaths = 2;</code>
   */
  java.util.List<? extends com.facebook.buck.cd.model.common.PathOrBuilder> 
      getContentPathsOrBuilderList();
  /**
   * <code>repeated .Path contentPaths = 2;</code>
   */
  com.facebook.buck.cd.model.common.PathOrBuilder getContentPathsOrBuilder(
      int index);
}