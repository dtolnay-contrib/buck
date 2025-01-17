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

package com.facebook.buck.apple.toolchain;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.dd.plist.NSArray;
import com.dd.plist.NSObject;
import com.dd.plist.NSString;
import com.google.common.base.Suppliers;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.HashCode;
import java.nio.file.Paths;
import java.util.Date;
import java.util.Optional;
import org.junit.Test;

public class ProvisioningProfileStoreTest {
  private static ProvisioningProfileMetadata makeTestMetadata(
      String appID, Date expirationDate, String uuid) {
    return makeTestMetadata(appID, expirationDate, uuid, ImmutableMap.of());
  }

  private static ProvisioningProfileMetadata makeTestMetadata(
      String appID, Date expirationDate, String uuid, ImmutableMap<String, NSObject> entitlements) {
    return makeTestMetadata(appID, expirationDate, uuid, entitlements, ImmutableSet.of());
  }

  private static ProvisioningProfileMetadata makeTestMetadata(
      String appID,
      Date expirationDate,
      String uuid,
      ImmutableMap<String, NSObject> entitlements,
      ImmutableSet<HashCode> fingerprints) {
    return ImmutableProvisioningProfileMetadata.builder()
        .setAppID(ProvisioningProfileMetadata.splitAppID(appID))
        .setExpirationDate(expirationDate)
        .setUUID(uuid)
        .setProfilePath(Paths.get("dummy.mobileprovision"))
        .setEntitlements(entitlements)
        .setDeveloperCertificateFingerprints(fingerprints)
        .build();
  }

  private static ProvisioningProfileStore createStorefromProvisioningProfiles(
      Iterable<ProvisioningProfileMetadata> profiles) {
    return ProvisioningProfileStore.of(Suppliers.ofInstance(ImmutableList.copyOf(profiles)));
  }

  @Test
  public void testExpiredProfilesAreIgnored() {
    ProvisioningProfileStore profiles =
        createStorefromProvisioningProfiles(
            ImmutableList.of(
                makeTestMetadata(
                    "AAAAAAAAAA.*", new Date(0), "00000000-0000-0000-0000-000000000000")));

    Optional<ProvisioningProfileMetadata> actual =
        profiles.getBestProvisioningProfile(
            "com.facebook.test",
            ApplePlatform.IPHONEOS,
            ProvisioningProfileStore.MATCH_ANY_ENTITLEMENT,
            ProvisioningProfileStore.MATCH_ANY_IDENTITY,
            new StringBuffer());

    assertThat(actual, is(equalTo(Optional.empty())));
  }

  @Test
  public void testPrefixOverride() {
    ProvisioningProfileMetadata expected =
        makeTestMetadata(
            "AAAAAAAAAA.*", new Date(Long.MAX_VALUE), "00000000-0000-0000-0000-000000000000");

    ProvisioningProfileStore profiles =
        createStorefromProvisioningProfiles(
            ImmutableList.of(
                expected,
                makeTestMetadata(
                    "BBBBBBBBBB.com.facebook.test",
                    new Date(Long.MAX_VALUE),
                    "00000000-0000-0000-0000-000000000000")));

    NSString[] fakeKeychainAccessGroups = {new NSString("AAAAAAAAAA.*")};
    ImmutableMap<String, NSObject> fakeEntitlements =
        ImmutableMap.of("keychain-access-groups", new NSArray(fakeKeychainAccessGroups));

    Optional<ProvisioningProfileMetadata> actual =
        profiles.getBestProvisioningProfile(
            "com.facebook.test",
            ApplePlatform.IPHONEOS,
            Optional.of(fakeEntitlements),
            ProvisioningProfileStore.MATCH_ANY_IDENTITY,
            new StringBuffer());

    assertThat(actual.get(), is(equalTo(expected)));
  }

  @Test
  public void testEntitlementKeysAreMatched() {
    NSString[] fakeKeychainAccessGroups = {new NSString("AAAAAAAAAA.*")};
    NSArray fakeKeychainAccessGroupsArray = new NSArray(fakeKeychainAccessGroups);

    ImmutableMap<String, NSObject> fakeDevelopmentEntitlements =
        ImmutableMap.of(
            "keychain-access-groups",
            fakeKeychainAccessGroupsArray,
            "aps-environment",
            new NSString("development"),
            "com.apple.security.application-groups",
            new NSArray(new NSString("foo"), new NSString("bar")));

    ImmutableMap<String, NSObject> fakeProductionEntitlements =
        ImmutableMap.of(
            "keychain-access-groups",
            fakeKeychainAccessGroupsArray,
            "aps-environment",
            new NSString("production"),
            "com.apple.security.application-groups",
            new NSArray(new NSString("foo"), new NSString("bar"), new NSString("baz")));

    ProvisioningProfileMetadata expected =
        makeTestMetadata(
            "AAAAAAAAAA.com.facebook.test",
            new Date(Long.MAX_VALUE),
            "11111111-1111-1111-1111-111111111111",
            fakeProductionEntitlements);

    ProvisioningProfileStore profiles =
        createStorefromProvisioningProfiles(
            ImmutableList.of(
                makeTestMetadata(
                    "AAAAAAAAAA.com.facebook.test",
                    new Date(Long.MAX_VALUE),
                    "00000000-0000-0000-0000-000000000000",
                    fakeDevelopmentEntitlements),
                expected));

    Optional<ProvisioningProfileMetadata> actual =
        profiles.getBestProvisioningProfile(
            "com.facebook.test",
            ApplePlatform.IPHONEOS,
            Optional.of(
                ImmutableMap.of(
                    "keychain-access-groups",
                    fakeKeychainAccessGroupsArray,
                    "aps-environment",
                    new NSString("production"),
                    "com.apple.security.application-groups",
                    new NSArray(new NSString("foo"), new NSString("bar")))),
            ProvisioningProfileStore.MATCH_ANY_IDENTITY,
            new StringBuffer());

    assertThat(actual.get(), is(equalTo(expected)));

    actual =
        profiles.getBestProvisioningProfile(
            "com.facebook.test",
            ApplePlatform.IPHONEOS,
            Optional.of(
                ImmutableMap.of(
                    "keychain-access-groups",
                    fakeKeychainAccessGroupsArray,
                    "aps-environment",
                    new NSString("production"),
                    "com.apple.security.application-groups",
                    new NSArray(new NSString("foo"), new NSString("xxx")))),
            ProvisioningProfileStore.MATCH_ANY_IDENTITY,
            new StringBuffer());
    assertFalse(actual.isPresent());

    // Test without keychain access groups.
    actual =
        profiles.getBestProvisioningProfile(
            "com.facebook.test",
            ApplePlatform.IPHONEOS,
            Optional.of(
                ImmutableMap.of(
                    "aps-environment",
                    new NSString("production"),
                    "com.apple.security.application-groups",
                    new NSArray(new NSString("foo"), new NSString("bar")))),
            ProvisioningProfileStore.MATCH_ANY_IDENTITY,
            new StringBuffer());

    assertThat(actual.get(), is(equalTo(expected)));

    actual =
        profiles.getBestProvisioningProfile(
            "com.facebook.test",
            ApplePlatform.IPHONEOS,
            Optional.of(
                ImmutableMap.of(
                    "aps-environment",
                    new NSString("production"),
                    "com.apple.security.application-groups",
                    new NSArray(new NSString("foo"), new NSString("xxx")))),
            ProvisioningProfileStore.MATCH_ANY_IDENTITY,
            new StringBuffer());
    assertFalse(actual.isPresent());
  }

  @Test
  public void testOnlyProfilesContainingValidFingerprintsAreMatched() {
    CodeSignIdentity validIdentity =
        CodeSignIdentity.of(
            CodeSignIdentity.toFingerprint("BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB"),
            "iPhone Developer: Foo Bar (54321EDCBA)");

    CodeSignIdentity otherIdentity =
        CodeSignIdentity.of(
            CodeSignIdentity.toFingerprint("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"),
            "iPhone Developer: Foo Bar (ABCDE12345)");

    ProvisioningProfileMetadata expected =
        makeTestMetadata(
            "AAAAAAAAAA.com.facebook.test",
            new Date(Long.MAX_VALUE),
            "11111111-1111-1111-1111-111111111111",
            ImmutableMap.of(),
            ImmutableSet.of(
                validIdentity.getFingerprint().get(), otherIdentity.getFingerprint().get()));

    ProvisioningProfileStore profiles =
        createStorefromProvisioningProfiles(
            ImmutableList.of(
                makeTestMetadata(
                    "AAAAAAAAAA.com.facebook.test",
                    new Date(Long.MAX_VALUE),
                    "00000000-0000-0000-0000-000000000000",
                    ImmutableMap.of(),
                    ImmutableSet.of(otherIdentity.getFingerprint().get())),
                expected));

    Optional<ProvisioningProfileMetadata> actual =
        profiles.getBestProvisioningProfile(
            "com.facebook.test",
            ApplePlatform.IPHONEOS,
            ProvisioningProfileStore.MATCH_ANY_ENTITLEMENT,
            Optional.of(ImmutableList.of(validIdentity)),
            new StringBuffer());

    assertThat(actual.get(), is(equalTo(expected)));
  }

  @Test
  public void testMatchesSpecificApp() {
    ProvisioningProfileMetadata expected =
        makeTestMetadata(
            "BBBBBBBBBB.com.facebook.test",
            new Date(Long.MAX_VALUE),
            "00000000-0000-0000-0000-000000000000");

    ProvisioningProfileStore profiles =
        createStorefromProvisioningProfiles(
            ImmutableList.of(
                expected,
                makeTestMetadata(
                    "BBBBBBBBBB.com.facebook.*",
                    new Date(Long.MAX_VALUE),
                    "11111111-1111-1111-1111-111111111111")));

    Optional<ProvisioningProfileMetadata> actual =
        profiles.getBestProvisioningProfile(
            "com.facebook.test",
            ApplePlatform.IPHONEOS,
            ProvisioningProfileStore.MATCH_ANY_ENTITLEMENT,
            ProvisioningProfileStore.MATCH_ANY_IDENTITY,
            new StringBuffer());

    assertThat(actual.get(), is(equalTo(expected)));
  }

  @Test
  public void testMatchesWildcard() {
    ProvisioningProfileMetadata expected =
        makeTestMetadata(
            "BBBBBBBBBB.*", new Date(Long.MAX_VALUE), "00000000-0000-0000-0000-000000000000");

    ProvisioningProfileStore profiles =
        createStorefromProvisioningProfiles(ImmutableList.of(expected));

    Optional<ProvisioningProfileMetadata> actual =
        profiles.getBestProvisioningProfile(
            "com.facebook.test",
            ApplePlatform.IPHONEOS,
            ProvisioningProfileStore.MATCH_ANY_ENTITLEMENT,
            ProvisioningProfileStore.MATCH_ANY_IDENTITY,
            new StringBuffer());

    assertThat(actual.get(), is(equalTo(expected)));
  }

  @Test
  public void testDiagnostics() {
    NSString[] fakeKeychainAccessGroups = {new NSString("AAAAAAAAAA.*")};
    NSArray fakeKeychainAccessGroupsArray = new NSArray(fakeKeychainAccessGroups);

    ImmutableMap<String, NSObject> fakeDevelopmentEntitlements =
        ImmutableMap.of(
            "keychain-access-groups",
            fakeKeychainAccessGroupsArray,
            "aps-environment",
            new NSString("development"),
            "com.apple.security.application-groups",
            new NSArray(new NSString("foobar"), new NSString("bar")));

    ProvisioningProfileStore profiles =
        createStorefromProvisioningProfiles(
            ImmutableList.of(
                makeTestMetadata(
                    "AAAAAAAAAA.com.facebook.test",
                    new Date(Long.MAX_VALUE),
                    "00000000-0000-0000-0000-000000000000",
                    fakeDevelopmentEntitlements)));

    StringBuffer diagnosticsBuffer = new StringBuffer();
    Optional<ProvisioningProfileMetadata> actual =
        profiles.getBestProvisioningProfile(
            "com.facebook.test",
            ApplePlatform.IPHONEOS,
            Optional.of(
                ImmutableMap.of(
                    "keychain-access-groups",
                    fakeKeychainAccessGroupsArray,
                    "aps-environment",
                    new NSString("production"),
                    "com.apple.security.application-groups",
                    new NSArray(new NSString("foo"), new NSString("bar")))),
            ProvisioningProfileStore.MATCH_ANY_IDENTITY,
            diagnosticsBuffer);
    String diagnostics = diagnosticsBuffer.toString();
    assertThat(
        diagnostics,
        containsString(
            "mismatched entitlement aps-environment;"
                + System.lineSeparator()
                + "value in provisioning profile is: development"
                + System.lineSeparator()
                + "but expected value from entitlements file: production"));
    assertThat(
        diagnostics,
        containsString(
            "mismatched entitlement com.apple.security.application-groups;"
                + System.lineSeparator()
                + "value in provisioning profile is: (\"foobar\", \"bar\")"
                + System.lineSeparator()
                + "but expected value from entitlements file: (\"foo\", \"bar\")"));
    assertFalse(actual.isPresent());
  }

  @Test
  public void testForceIncludedAppEntitlements() {
    NSString[] fakeKeychainAccessGroups = {new NSString("AAAAAAAAAA.*")};
    NSArray fakeKeychainAccessGroupsArray = new NSArray(fakeKeychainAccessGroups);

    ImmutableMap<String, NSObject> entitlementsInProfile =
        ImmutableMap.of(
            "keychain-access-groups",
            fakeKeychainAccessGroupsArray,
            "aps-environment",
            new NSString("production"));

    ProvisioningProfileStore profiles =
        createStorefromProvisioningProfiles(
            ImmutableList.of(
                makeTestMetadata(
                    "AAAAAAAAAA.com.facebook.test",
                    new Date(Long.MAX_VALUE),
                    "00000000-0000-0000-0000-000000000000",
                    entitlementsInProfile)));

    ImmutableMap<String, NSObject> entitlementsInApp =
        ImmutableMap.of(
            "application-identifier", // Force included key, even if not present in the profile
            new NSString("AAAAAAAAAA.com.facebook.BuckApp"),
            "keychain-access-groups",
            fakeKeychainAccessGroupsArray,
            "aps-environment",
            new NSString("production"));

    StringBuffer diagnosticsBuffer = new StringBuffer();
    Optional<ProvisioningProfileMetadata> maybeBestProfile =
        profiles.getBestProvisioningProfile(
            "com.facebook.test",
            ApplePlatform.IPHONEOS,
            Optional.of(entitlementsInApp),
            ProvisioningProfileStore.MATCH_ANY_IDENTITY,
            diagnosticsBuffer);

    assertTrue(maybeBestProfile.isPresent());
  }

  @Test
  public void testUnmatchedAppEntitlement() {
    NSString[] fakeKeychainAccessGroups = {new NSString("AAAAAAAAAA.*")};
    NSArray fakeKeychainAccessGroupsArray = new NSArray(fakeKeychainAccessGroups);

    ImmutableMap<String, NSObject> entitlementsInProfile =
        ImmutableMap.of(
            "keychain-access-groups",
            fakeKeychainAccessGroupsArray,
            "aps-environment",
            new NSString("production"));

    ProvisioningProfileStore profiles =
        createStorefromProvisioningProfiles(
            ImmutableList.of(
                makeTestMetadata(
                    "AAAAAAAAAA.com.facebook.test",
                    new Date(Long.MAX_VALUE),
                    "00000000-0000-0000-0000-000000000000",
                    entitlementsInProfile)));

    ImmutableMap<String, NSObject> entitlementsInApp =
        ImmutableMap.of(
            "keychain-access-groups",
            fakeKeychainAccessGroupsArray,
            "aps-environment",
            new NSString("production"),
            "com.made.up.entitlement",
            new NSString("buck"));

    StringBuffer diagnosticsBuffer = new StringBuffer();
    Optional<ProvisioningProfileMetadata> maybeBestProfile =
        profiles.getBestProvisioningProfile(
            "com.facebook.test",
            ApplePlatform.IPHONEOS,
            Optional.of(entitlementsInApp),
            ProvisioningProfileStore.MATCH_ANY_IDENTITY,
            diagnosticsBuffer);

    assertFalse(maybeBestProfile.isPresent());

    String diagnostics = diagnosticsBuffer.toString();
    assertThat(
        diagnostics,
        containsString(
            "mismatched entitlement com.made.up.entitlement;"
                + System.lineSeparator()
                + "value in provisioning profile is: (not set)"
                + System.lineSeparator()
                + "but expected value from entitlements file: buck"));
  }
}
