// @generated
// Generated by the protocol buffer compiler.  DO NOT EDIT!
// source: cd/resources/proto/javacd.proto

package com.facebook.buck.cd.model.java;

@javax.annotation.Generated(value="protoc", comments="annotations:LibraryJarCommandOrBuilder.java.pb.meta")
public interface LibraryJarCommandOrBuilder extends
    // @@protoc_insertion_point(interface_extends:javacd.api.v1.LibraryJarCommand)
    com.google.protobuf.MessageOrBuilder {

  /**
   * <code>.javacd.api.v1.BaseJarCommand baseJarCommand = 1;</code>
   */
  boolean hasBaseJarCommand();
  /**
   * <code>.javacd.api.v1.BaseJarCommand baseJarCommand = 1;</code>
   */
  com.facebook.buck.cd.model.java.BaseJarCommand getBaseJarCommand();
  /**
   * <code>.javacd.api.v1.BaseJarCommand baseJarCommand = 1;</code>
   */
  com.facebook.buck.cd.model.java.BaseJarCommandOrBuilder getBaseJarCommandOrBuilder();

  /**
   * <code>.javacd.api.v1.LibraryJarBaseCommand libraryJarBaseCommand = 2;</code>
   */
  boolean hasLibraryJarBaseCommand();
  /**
   * <code>.javacd.api.v1.LibraryJarBaseCommand libraryJarBaseCommand = 2;</code>
   */
  com.facebook.buck.cd.model.java.LibraryJarBaseCommand getLibraryJarBaseCommand();
  /**
   * <code>.javacd.api.v1.LibraryJarBaseCommand libraryJarBaseCommand = 2;</code>
   */
  com.facebook.buck.cd.model.java.LibraryJarBaseCommandOrBuilder getLibraryJarBaseCommandOrBuilder();
}
