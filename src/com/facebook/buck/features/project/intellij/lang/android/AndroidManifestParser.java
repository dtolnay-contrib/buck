/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.buck.features.project.intellij.lang.android;

import com.facebook.buck.core.util.log.Logger;
import com.facebook.buck.io.filesystem.ProjectFilesystem;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

public class AndroidManifestParser {

  private static final Logger LOG = Logger.get(AndroidManifestParser.class);

  private final ProjectFilesystem projectFilesystem;

  public AndroidManifestParser(ProjectFilesystem projectFilesystem) {
    this.projectFilesystem = projectFilesystem;
  }

  /**
   * Parse the minSdkVersion from a <uses-sdk> node from the Android manifest file, if any.
   *
   * @param androidManifestPath The path of the Android manifest file.
   * @return An optional containing the minSdkVersion.
   */
  public Optional<String> parseMinSdkVersion(Path androidManifestPath) {
    Optional<Document> manifestDocument =
        readAndroidManifestDocument(projectFilesystem.getPathForRelativePath(androidManifestPath));
    if (!manifestDocument.isPresent()) {
      return Optional.empty();
    }
    String minSdkVersion = null;
    try {
      XPath xPath = XPathFactory.newInstance().newXPath();
      NodeList usesSdkNodes =
          (NodeList) xPath.evaluate("//uses-sdk", manifestDocument.get(), XPathConstants.NODESET);
      if (usesSdkNodes.getLength() > 0) {
        Node usesSdkNode = usesSdkNodes.item(0);
        NamedNodeMap attrs = usesSdkNode.getAttributes();
        if (attrs.getLength() > 0) {
          Node minSdkVersionAttribute =
              usesSdkNode.getAttributes().getNamedItem("android:minSdkVersion");
          if (minSdkVersionAttribute != null) {
            minSdkVersion = minSdkVersionAttribute.getNodeValue();
          }
        }
      }
    } catch (XPathExpressionException e) {
      LOG.debug(
          e, "Cannot find android:minSdkVersion attribute in the manifest %s", androidManifestPath);
    }

    return Optional.ofNullable(minSdkVersion);
  }

  /**
   * Parse the package name defined in the Android manifest file, if any.
   *
   * @param androidManifestPath The path of the Android manifest file.
   * @return An optional containing the package name.
   */
  public Optional<String> parsePackage(Path androidManifestPath) {
    Optional<Document> manifestDocument =
        readAndroidManifestDocument(projectFilesystem.getPathForRelativePath(androidManifestPath));
    if (!manifestDocument.isPresent()) {
      return Optional.empty();
    }
    String packageName = null;
    try {
      XPath xPath = XPathFactory.newInstance().newXPath();
      NodeList manifestNodes =
          (NodeList) xPath.evaluate("/manifest", manifestDocument.get(), XPathConstants.NODESET);
      if (manifestNodes.getLength() > 0) {
        Node manifestNode = manifestNodes.item(0);
        NamedNodeMap attrs = manifestNode.getAttributes();
        if (attrs.getLength() > 0) {
          Node packageAttribute = manifestNode.getAttributes().getNamedItem("package");
          if (packageAttribute != null) {
            packageName = packageAttribute.getNodeValue();
          }
        }
      }
    } catch (XPathExpressionException e) {
      LOG.debug(e, "Cannot find package attribute in the manifest %s", androidManifestPath);
    }

    return Optional.ofNullable(packageName);
  }

  /**
   * Parse any <uses-permission> node from the Android manifest file.
   *
   * @param androidManifestPath The path of the Android manifest file.
   * @return A set containing permission names. If there is no permission defined in manifest, an
   *     empty set is returned.
   */
  public ImmutableSet<String> parsePermissions(Path androidManifestPath) {
    final Optional<Document> manifestDocument =
        readAndroidManifestDocument(projectFilesystem.getPathForRelativePath(androidManifestPath));
    if (!manifestDocument.isPresent()) {
      return ImmutableSet.of();
    }

    final ImmutableSet.Builder<String> permissionSetBuilder = new ImmutableSet.Builder<>();
    try {
      final XPath xPath = XPathFactory.newInstance().newXPath();
      final NodeList usesPermissionNodes =
          (NodeList)
              xPath.evaluate("//uses-permission", manifestDocument.get(), XPathConstants.NODESET);
      for (int i = 0; i < usesPermissionNodes.getLength(); i++) {
        final Node node = usesPermissionNodes.item(i);
        final NamedNodeMap attrs = node.getAttributes();
        if (attrs.getLength() > 0) {
          final Node permissionAttribute = node.getAttributes().getNamedItem("android:name");
          if (permissionAttribute != null) {
            final String permissionName = permissionAttribute.getNodeValue();
            if (!Strings.isNullOrEmpty(permissionName)) {
              permissionSetBuilder.add(permissionName);
            }
          }
        }
      }
    } catch (XPathExpressionException e) {
      LOG.debug(e, "Cannot find permission in the manifest %s", androidManifestPath);
    }
    return permissionSetBuilder.build();
  }

  private Optional<Document> readAndroidManifestDocument(Path androidManifestPath) {
    File manifestFile = androidManifestPath.toFile();
    if (!manifestFile.exists()) {
      return Optional.empty();
    }

    try {
      DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();
      DocumentBuilder builder = documentBuilderFactory.newDocumentBuilder();
      return Optional.of(builder.parse(manifestFile));
    } catch (ParserConfigurationException | SAXException | IOException e) {
      LOG.debug(e, "Cannot parse XML from %s", androidManifestPath);
    }
    return Optional.empty();
  }
}
