// @generated
// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: cd/resources/proto/common.proto

package com.facebook.buck.cd.model.common;

@javax.annotation.Generated(value="protoc", comments="annotations:RelPathMapEntryOrBuilder.java.pb.meta")
public interface RelPathMapEntryOrBuilder extends
    // @@protoc_insertion_point(interface_extends:RelPathMapEntry)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <code>.RelPath key = 1;</code>
   */
  boolean hasKey();
  /**
   * <code>.RelPath key = 1;</code>
   */
  com.facebook.buck.cd.model.common.RelPath getKey();
  /**
   * <code>.RelPath key = 1;</code>
   */
  com.facebook.buck.cd.model.common.RelPathOrBuilder getKeyOrBuilder();

  /**
   * <code>.RelPath value = 2;</code>
   */
  boolean hasValue();
  /**
   * <code>.RelPath value = 2;</code>
   */
  com.facebook.buck.cd.model.common.RelPath getValue();
  /**
   * <code>.RelPath value = 2;</code>
   */
  com.facebook.buck.cd.model.common.RelPathOrBuilder getValueOrBuilder();
}
