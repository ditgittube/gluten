/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#include <filesystem>

#include "VeloxInitializer.h"

#include <folly/executors/IOThreadPoolExecutor.h>

#include "RegistrationAllFunctions.h"
#include "VeloxBridge.h"
#include "config/GlutenConfig.h"
#include "velox/common/file/FileSystems.h"
#ifdef VELOX_ENABLE_HDFS
#include "velox/connectors/hive/storage_adapters/hdfs/HdfsFileSystem.h"
#endif
#ifdef VELOX_ENABLE_S3
#include "velox/connectors/hive/storage_adapters/s3fs/S3FileSystem.h"
#endif
#include "velox/common/memory/MmapAllocator.h"
#include "velox/dwio/dwrf/reader/DwrfReader.h"
#include "velox/dwio/parquet/RegisterParquetReader.h"
#include "velox/exec/Operator.h"

DECLARE_int32(split_preload_per_driver);

using namespace facebook;

namespace gluten {

std::shared_ptr<velox::memory::MemoryAllocator> VeloxInitializer::asyncDataCache_;

void VeloxInitializer::Init(std::unordered_map<std::string, std::string>& conf) {
  // Setup and register.
  velox::filesystems::registerLocalFileSystem();

  std::unordered_map<std::string, std::string> configurationValues;

#ifdef VELOX_ENABLE_HDFS
  velox::filesystems::registerHdfsFileSystem();
  std::unordered_map<std::string, std::string> hdfsConfig({});

  std::string hdfsUri = conf["spark.hadoop.fs.defaultFS"];
  const char* envHdfsUri = std::getenv("VELOX_HDFS");
  if (envHdfsUri != nullptr) {
    hdfsUri = std::string(envHdfsUri);
  }

  auto hdfsHostWithPort = hdfsUri.substr(hdfsUri.find(':') + 3);
  std::size_t pos = hdfsHostWithPort.find(':');
  if (pos != std::string::npos) {
    auto hdfsPort = hdfsHostWithPort.substr(pos + 1);
    auto hdfsHost = hdfsHostWithPort.substr(0, pos);
    hdfsConfig.insert({{"hive.hdfs.host", hdfsHost}, {"hive.hdfs.port", hdfsPort}});
  } else {
    // For HDFS HA mode. In this case, hive.hdfs.host should be the nameservice, we can
    // get it from HDFS uri, and hive.hdfs.port should be an empty string, and the HDFS HA
    // configurations should be taken from the LIBHDFS3_CONF file.
    hdfsConfig.insert({{"hive.hdfs.host", hdfsHostWithPort}, {"hive.hdfs.port", ""}});
  }
  configurationValues.merge(hdfsConfig);
#endif

#ifdef VELOX_ENABLE_S3
  velox::filesystems::registerS3FileSystem();

  std::string awsAccessKey = conf["spark.hadoop.fs.s3a.access.key"];
  std::string awsSecretKey = conf["spark.hadoop.fs.s3a.secret.key"];
  std::string awsEndpoint = conf["spark.hadoop.fs.s3a.endpoint"];
  std::string sslEnabled = conf["spark.hadoop.fs.s3a.connection.ssl.enabled"];
  std::string pathStyleAccess = conf["spark.hadoop.fs.s3a.path.style.access"];
  std::string useInstanceCredentials = conf["spark.hadoop.fs.s3a.use.instance.credentials"];

  const char* envAwsAccessKey = std::getenv("AWS_ACCESS_KEY_ID");
  if (envAwsAccessKey != nullptr) {
    awsAccessKey = std::string(envAwsAccessKey);
  }
  const char* envAwsSecretKey = std::getenv("AWS_SECRET_ACCESS_KEY");
  if (envAwsSecretKey != nullptr) {
    awsSecretKey = std::string(envAwsSecretKey);
  }
  const char* envAwsEndpoint = std::getenv("AWS_ENDPOINT");
  if (envAwsEndpoint != nullptr) {
    awsEndpoint = std::string(envAwsEndpoint);
  }

  std::unordered_map<std::string, std::string> S3Config({});
  if (useInstanceCredentials == "true") {
    S3Config.insert({
        {"hive.s3.use-instance-credentials", useInstanceCredentials},
    });
  } else {
    S3Config.insert({
        {"hive.s3.aws-access-key", awsAccessKey},
        {"hive.s3.aws-secret-key", awsSecretKey},
        {"hive.s3.endpoint", awsEndpoint},
        {"hive.s3.ssl.enabled", sslEnabled},
        {"hive.s3.path-style-access", pathStyleAccess},
    });
  }
  configurationValues.merge(S3Config);
#endif

  InitCache(conf);

  auto properties = std::make_shared<const velox::core::MemConfig>(configurationValues);
  auto hiveConnector =
      velox::connector::getConnectorFactory(velox::connector::hive::HiveConnectorFactory::kHiveConnectorName)
          ->newConnector(kHiveConnectorId, properties, ioExecutor_.get());
  if (ioExecutor_) {
    FLAGS_split_preload_per_driver = 0;
  }

  registerConnector(hiveConnector);
  velox::parquet::registerParquetReaderFactory(velox::parquet::ParquetReaderType::NATIVE);
  velox::dwrf::registerDwrfReaderFactory();
  // Register Velox functions
  registerAllFunctions();
}

velox::memory::MemoryAllocator* VeloxInitializer::getAsyncDataCache() {
  if (asyncDataCache_ != nullptr) {
    return asyncDataCache_.get();
  }
  return velox::memory::MemoryAllocator::getInstance();
}

void VeloxInitializer::InitCache(std::unordered_map<std::string, std::string>& conf) {
  auto key = conf.find(kVeloxCacheEnabled);
  if (key != conf.end() && boost::algorithm::to_lower_copy(conf[kVeloxCacheEnabled]) == "true") {
    FLAGS_ssd_odirect = true;
    if (conf.find(kVeloxSsdODirectEnabled) != conf.end() &&
        boost::algorithm::to_lower_copy(conf[kVeloxSsdODirectEnabled]) == "false") {
      FLAGS_ssd_odirect = false;
    }
    uint64_t memCacheSize = std::stol(kVeloxMemCacheSizeDefault);
    uint64_t ssdCacheSize = std::stol(kVeloxSsdCacheSizeDefault);
    int32_t ssdCacheShards = std::stoi(kVeloxSsdCacheShardsDefault);
    int32_t ssdCacheIOThreads = std::stoi(kVeloxSsdCacheIOThreadsDefault);
    int32_t ioThreads = std::stoi(kVeloxIOThreadsDefault);
    std::string ssdCachePathPrefix = kVeloxSsdCachePathDefault;
    for (auto& [k, v] : conf) {
      if (k == kVeloxMemCacheSize)
        memCacheSize = std::stol(v);
      if (k == kVeloxSsdCacheSize)
        ssdCacheSize = std::stol(v);
      if (k == kVeloxSsdCacheShards)
        ssdCacheShards = std::stoi(v);
      if (k == kVeloxSsdCachePath)
        ssdCachePathPrefix = v;
      if (k == kVeloxIOThreads)
        ioThreads = std::stoi(v);
      if (k == kVeloxSsdCacheIOThreads)
        ssdCacheIOThreads = std::stoi(v);
    }
    std::string ssdCachePath = ssdCachePathPrefix + "/cache." + genUuid() + ".";
    ssdCacheExecutor_ = std::make_unique<folly::IOThreadPoolExecutor>(ssdCacheIOThreads);
    ioExecutor_ = std::make_unique<folly::IOThreadPoolExecutor>(ioThreads);
    auto ssd =
        std::make_unique<velox::cache::SsdCache>(ssdCachePath, ssdCacheSize, ssdCacheShards, ssdCacheExecutor_.get());

    std::error_code ec;
    const std::filesystem::space_info si = std::filesystem::space(ssdCachePathPrefix, ec);
    if (si.available < ssdCacheSize) {
      VELOX_FAIL(
          "not enough space for ssd cache in " + ssdCachePath + " cache size: " + std::to_string(ssdCacheSize) +
          "free space: " + std::to_string(si.available))
    }

    velox::memory::MmapAllocator::Options options;
    options.capacity = memCacheSize;
    auto allocator = std::make_shared<velox::memory::MmapAllocator>(options);
    asyncDataCache_ = std::make_shared<velox::cache::AsyncDataCache>(allocator, memCacheSize, std::move(ssd));

    VELOX_CHECK_NOT_NULL(dynamic_cast<velox::cache::AsyncDataCache*>(asyncDataCache_.get()))
    LOG(INFO) << "STARTUP: Using AsyncDataCache memory cache size: " << memCacheSize
              << ", ssdCache prefix: " << ssdCachePath << ", ssdCache size: " << ssdCacheSize
              << ", ssdCache shards: " << ssdCacheShards << ", ssdCache IO threads: " << ssdCacheIOThreads
              << ", IO threads: " << ioThreads;
  }
}

} // namespace gluten