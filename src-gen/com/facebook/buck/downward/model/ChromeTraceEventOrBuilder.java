// @generated
// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: downwardapi/resources/proto/downward_api.proto

package com.facebook.buck.downward.model;

@javax.annotation.Generated(value="protoc", comments="annotations:ChromeTraceEventOrBuilder.java.pb.meta")
public interface ChromeTraceEventOrBuilder extends
    // @@protoc_insertion_point(interface_extends:downward.api.v1.ChromeTraceEvent)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <pre>
   * required for matching begin to end events for downward API. Does not map to a field in
   * SimplePerfEvent
   * </pre>
   *
   * <code>int32 event_id = 1;</code>
   */
  int getEventId();

  /**
   * <code>string category = 2;</code>
   */
  java.lang.String getCategory();
  /**
   * <code>string category = 2;</code>
   */
  com.google.protobuf.ByteString
      getCategoryBytes();

  /**
   * <code>.downward.api.v1.ChromeTraceEvent.ChromeTraceEventStatus status = 3;</code>
   */
  int getStatusValue();
  /**
   * <code>.downward.api.v1.ChromeTraceEvent.ChromeTraceEventStatus status = 3;</code>
   */
  com.facebook.buck.downward.model.ChromeTraceEvent.ChromeTraceEventStatus getStatus();

  /**
   * <pre>
   * just included into chrome trace
   * </pre>
   *
   * <code>map&lt;string, string&gt; data = 4;</code>
   */
  int getDataCount();
  /**
   * <pre>
   * just included into chrome trace
   * </pre>
   *
   * <code>map&lt;string, string&gt; data = 4;</code>
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
   * <pre>
   * just included into chrome trace
   * </pre>
   *
   * <code>map&lt;string, string&gt; data = 4;</code>
   */
  java.util.Map<java.lang.String, java.lang.String>
  getDataMap();
  /**
   * <pre>
   * just included into chrome trace
   * </pre>
   *
   * <code>map&lt;string, string&gt; data = 4;</code>
   */

  java.lang.String getDataOrDefault(
      java.lang.String key,
      java.lang.String defaultValue);
  /**
   * <pre>
   * just included into chrome trace
   * </pre>
   *
   * <code>map&lt;string, string&gt; data = 4;</code>
   */

  java.lang.String getDataOrThrow(
      java.lang.String key);

  /**
   * <pre>
   * relative time duration from the beginning of the tool invocation
   * </pre>
   *
   * <code>.google.protobuf.Duration duration = 5;</code>
   */
  boolean hasDuration();
  /**
   * <pre>
   * relative time duration from the beginning of the tool invocation
   * </pre>
   *
   * <code>.google.protobuf.Duration duration = 5;</code>
   */
  com.google.protobuf.Duration getDuration();
  /**
   * <pre>
   * relative time duration from the beginning of the tool invocation
   * </pre>
   *
   * <code>.google.protobuf.Duration duration = 5;</code>
   */
  com.google.protobuf.DurationOrBuilder getDurationOrBuilder();

  /**
   * <code>string title = 6;</code>
   */
  java.lang.String getTitle();
  /**
   * <code>string title = 6;</code>
   */
  com.google.protobuf.ByteString
      getTitleBytes();

  /**
   * <code>string action_id = 7;</code>
   */
  java.lang.String getActionId();
  /**
   * <code>string action_id = 7;</code>
   */
  com.google.protobuf.ByteString
      getActionIdBytes();
}
