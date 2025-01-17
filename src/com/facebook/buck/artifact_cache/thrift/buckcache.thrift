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

namespace java com.facebook.buck.artifact_cache.thrift

enum BuckCacheRequestType {
  UNKNOWN = 0,
  FETCH = 100,
  STORE = 101,
  MULTI_FETCH = 102,
  // `DELETE` is a define somewhere inside glibc
  DELETE_REQUEST = 105,
  CONTAINS = 107,
}

struct RuleKey {
  1: optional string hashString;
}

struct ArtifactMetadata {
  1: optional list<RuleKey> ruleKeys;
  2: optional map<string, string> metadata;
  3: optional string buildTarget;
  4: optional string repository;
  5: optional string artifactPayloadCrc32;  // DEPRECATED: Will be removed soon.
  6: optional string scheduleType;
  7: optional string artifactPayloadMd5;
  // 8: DEPRECATED.
  // Free-form identifier of a service that produced the artifact
  9: optional string producerId;
  // How long it took to build this artifact, in milliseconds
  10: optional i64 buildTimeMs;
  // Hostname of a machine that produced the artifact
  11: optional string producerHostname;
  // Size of the content in bytes
  12: optional i64 sizeBytes;
  13: optional string configuration;
}

enum ContainsResultType {
  CONTAINS = 0,
  DOES_NOT_CONTAIN = 1,
  UNKNOWN_DUE_TO_TRANSIENT_ERRORS = 2,
}

struct ContainsDebugInfo {
  // Fastest store to return a cache hit.
  1: optional string fastestCacheHitStore;
  // The store ID, indicating ZippyDB or Memcached, to return a cache hit.
  2: optional string fastestCacheHitStoreId;
}

struct ContainsResult {
  1: optional ContainsResultType resultType;
  2: optional ContainsDebugInfo debugInfo;
}

struct FetchDebugInfo {
  // All stores used to look up the artifact.
  1: optional list<string> storesLookedUp;

  // 2: DEPRECATED.

  // Fastest store to return a cache hit.
  3: optional string fastestCacheHitStore;
  // The store ID, indicating ZippyDB or Memcached, to return a cache hit.
  4: optional string fastestCacheHitStoreId;
}

struct StoreDebugInfo {
  // All stores used in the write.
  1: optional list<string> storesWrittenInto;
  2: optional i64 artifactSizeBytes;
}

struct BuckCacheStoreRequest {
  1: optional ArtifactMetadata metadata;

  // If this field is not present then the payload is passed via a different
  // out of band method.
  100: optional binary payload;
}

struct BuckCacheStoreResponse {
  1: optional StoreDebugInfo debugInfo;
}

struct BuckCacheFetchRequest {
  1: optional RuleKey ruleKey;
  2: optional string repository;
  3: optional string scheduleType;
  // 4: DEPRECATED.
  // The fully qualified target name associated with the ruleKey
  5: optional string buildTarget;
}

struct BuckCacheFetchResponse {
  1: optional bool artifactExists;
  2: optional ArtifactMetadata metadata;
  3: optional FetchDebugInfo debugInfo;

  // If this field is not present then the payload is passed via a different
  // out of band method.
  100: optional binary payload;
}

// NOTE: The contains request is only supposed to be best-effort. A CONTAINS
// result only means that it is highly likely that we contain the artifact.
// And a DOES_NOT_CONTAIN result means that it might still be present in stores
// like Memcache, where we do not have a contains check. The third result type
// of UNKNOWN_DUE_TO_TRANSIENT_ERRORS means that some stores returned a MISS,
// while others errored out.
struct BuckCacheMultiContainsRequest {
  1: optional list<RuleKey> ruleKeys;
  2: optional string repository;
  3: optional string scheduleType;
  // 4: DEPRECATED.
}

struct BuckCacheMultiContainsResponse {
  1: optional list<ContainsResult> results;

  // All stores used to look up the artifact.
  2: optional list<string> storesLookedUp;
}

enum FetchResultType {
  UNKNOWN = 0,
  HIT = 100,
  MISS = 101,
  // CONTAINS indicates that the cache contains an artifact for the key, but
  // could not return it in this request due to resource constraints. The key
  // should be requested again (possibly in a single-key request to ensure
  // resources are available to service the request).
  CONTAINS = 102
  // SKIPPED indicates that, due to resource constraints, no information about
  // the requested key was looked up. The key should be requested again.
  SKIPPED = 103,
  ERROR = 104,
  MISS_ONLY_IN_MEMCACHE = 105,
  MISS_IN_SLA = 106,
  MISS_OUT_SLA = 107,
  MISS_UNKNOWN = 108,
}

struct FetchResult {
  1: optional FetchResultType resultType;
  2: optional ArtifactMetadata metadata;
  3: optional FetchDebugInfo debugInfo;

  // If this field is not present then the payload is passed via a different
  // out of band method.
  100: optional binary payload;
}

struct BuckCacheMultiFetchRequest {
  1: optional list<RuleKey> ruleKeys;
  2: optional string repository;
  3: optional string scheduleType;
  // 4: DEPRECATED.
  // Fully qualified target names associated with the rulekeys. There should
  // always be the same number of these as ruleKeys, and the entries should
  // match 1:1 (aka buildTargets[2] is associated with ruleKeys[2]).
  // RuleKeys that don't have an associated build target (for whatever reason)
  // will have an entry of "" (aka empty string).
  5: optional list<string> buildTargets;
}

struct BuckCacheMultiFetchResponse {
  1: optional list<FetchResult> results;
}

struct PayloadInfo {
  1: optional i64 sizeBytes;
}

struct BuckCacheDeleteRequest {
  1: optional list<RuleKey> ruleKeys;
  2: optional string repository;
  3: optional string scheduleType;
  // 4: DEPRECATED.
}

struct DeleteDebugInfo {
  1: optional list<string> storesDeletedFrom;
}

struct BuckCacheDeleteResponse {
  1: optional DeleteDebugInfo debugInfo;
}

struct BuckCacheRequest {
  1: optional BuckCacheRequestType type = BuckCacheRequestType.UNKNOWN;

  // Can be unset if request is not initiated by buck build,
  // e. g. if buckcache is called by command-line fetch utility.
  2: optional string buckBuildId;

  100: optional list<PayloadInfo> payloads;
  101: optional BuckCacheFetchRequest fetchRequest;
  102: optional BuckCacheStoreRequest storeRequest;
  103: optional BuckCacheMultiFetchRequest multiFetchRequest;
  105: optional BuckCacheDeleteRequest deleteRequest;
  107: optional BuckCacheMultiContainsRequest multiContainsRequest;
}

struct BuckCacheResponse {
  1: optional bool wasSuccessful;
  2: optional string errorMessage;

  10: optional BuckCacheRequestType type = BuckCacheRequestType.UNKNOWN;

  // 30: DEPRECATED.

  // Human-readable single-line server info.
  // Can be used only for debugging.
  31: optional string diagnosticServerInfo;

  100: optional list<PayloadInfo> payloads;
  101: optional BuckCacheFetchResponse fetchResponse;
  102: optional BuckCacheStoreResponse storeResponse;
  103: optional BuckCacheMultiFetchResponse multiFetchResponse;
  105: optional BuckCacheDeleteResponse deleteResponse;
  107: optional BuckCacheMultiContainsResponse multiContainsResponse;
}
