// @generated
// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: downwardapi/resources/proto/downward_api.proto

package com.facebook.buck.downward.model;

@javax.annotation.Generated(value="protoc", comments="annotations:ExternalEventOrBuilder.java.pb.meta")
public interface ExternalEventOrBuilder extends
    // @@protoc_insertion_point(interface_extends:downward.api.v1.ExternalEvent)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <code>map&lt;string, string&gt; data = 1;</code>
   */
  int getDataCount();
  /**
   * <code>map&lt;string, string&gt; data = 1;</code>
   */
  boolean containsData(
      java.lang.String key);
  /**
   * Use {@link #getDataMap()} instead.
   */
  @java.lang.Deprecated
  java.util.Map<java.lang.String, java.lang.String>
  getData();
  /**
   * <code>map&lt;string, string&gt; data = 1;</code>
   */
  java.util.Map<java.lang.String, java.lang.String>
  getDataMap();
  /**
   * <code>map&lt;string, string&gt; data = 1;</code>
   */

  java.lang.String getDataOrDefault(
      java.lang.String key,
      java.lang.String defaultValue);
  /**
   * <code>map&lt;string, string&gt; data = 1;</code>
   */

  java.lang.String getDataOrThrow(
      java.lang.String key);
}
