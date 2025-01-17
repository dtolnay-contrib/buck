// @generated
// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: remoteexecution/proto/metadata.proto

package com.facebook.buck.remoteexecution.proto;

@javax.annotation.Generated(value="protoc", comments="annotations:BuckInfoOrBuilder.java.pb.meta")
public interface BuckInfoOrBuilder extends
    // @@protoc_insertion_point(interface_extends:facebook.remote_execution.BuckInfo)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <pre>
   * The buck build id of the command starting the remote execution session.
   * </pre>
   *
   * <code>string build_id = 1;</code>
   */
  java.lang.String getBuildId();
  /**
   * <pre>
   * The buck build id of the command starting the remote execution session.
   * </pre>
   *
   * <code>string build_id = 1;</code>
   */
  com.google.protobuf.ByteString
      getBuildIdBytes();

  /**
   * <pre>
   * Name of the Build Rule that's being executed
   * </pre>
   *
   * <code>string rule_name = 2;</code>
   */
  java.lang.String getRuleName();
  /**
   * <pre>
   * Name of the Build Rule that's being executed
   * </pre>
   *
   * <code>string rule_name = 2;</code>
   */
  com.google.protobuf.ByteString
      getRuleNameBytes();

  /**
   * <pre>
   * Auxiliary tag set for builds with non-standard configurations.
   * </pre>
   *
   * <code>string auxiliary_build_tag = 3;</code>
   */
  java.lang.String getAuxiliaryBuildTag();
  /**
   * <pre>
   * Auxiliary tag set for builds with non-standard configurations.
   * </pre>
   *
   * <code>string auxiliary_build_tag = 3;</code>
   */
  com.google.protobuf.ByteString
      getAuxiliaryBuildTagBytes();

  /**
   * <pre>
   * Prefix for the top level target that was passed to 'buck build'
   * If multiple targets were passed, this is the common prefix (if there is one)
   * Note: project_prefix is not necessarily the same as the prefix for the specific action
   * that is being executed right now
   * </pre>
   *
   * <code>string project_prefix = 4;</code>
   */
  java.lang.String getProjectPrefix();
  /**
   * <pre>
   * Prefix for the top level target that was passed to 'buck build'
   * If multiple targets were passed, this is the common prefix (if there is one)
   * Note: project_prefix is not necessarily the same as the prefix for the specific action
   * that is being executed right now
   * </pre>
   *
   * <code>string project_prefix = 4;</code>
   */
  com.google.protobuf.ByteString
      getProjectPrefixBytes();

  /**
   * <pre>
   * Buck version
   * </pre>
   *
   * <code>string version = 5;</code>
   */
  java.lang.String getVersion();
  /**
   * <pre>
   * Buck version
   * </pre>
   *
   * <code>string version = 5;</code>
   */
  com.google.protobuf.ByteString
      getVersionBytes();

  /**
   * <pre>
   * Type of the build rule being executed.
   * </pre>
   *
   * <code>string rule_type = 6;</code>
   */
  java.lang.String getRuleType();
  /**
   * <pre>
   * Type of the build rule being executed.
   * </pre>
   *
   * <code>string rule_type = 6;</code>
   */
  com.google.protobuf.ByteString
      getRuleTypeBytes();
}
