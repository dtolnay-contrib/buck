// @generated
// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: cd/resources/proto/javacd.proto

package com.facebook.buck.cd.model.java;

@javax.annotation.Generated(value="protoc", comments="annotations:BaseCommandParamsOrBuilder.java.pb.meta")
public interface BaseCommandParamsOrBuilder extends
    // @@protoc_insertion_point(interface_extends:javacd.api.v1.BaseCommandParams)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <code>.javacd.api.v1.BaseCommandParams.SpoolMode spoolMode = 1;</code>
   */
  int getSpoolModeValue();
  /**
   * <code>.javacd.api.v1.BaseCommandParams.SpoolMode spoolMode = 1;</code>
   */
  com.facebook.buck.cd.model.java.BaseCommandParams.SpoolMode getSpoolMode();

  /**
   * <code>bool hasAnnotationProcessing = 2;</code>
   */
  boolean getHasAnnotationProcessing();

  /**
   * <code>bool withDownwardApi = 3;</code>
   */
  boolean getWithDownwardApi();
}
