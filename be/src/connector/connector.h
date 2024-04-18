// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#pragma once

#include <memory>
#include <string>
#include <unordered_map>

#include "connector_chunk_sink.h"
#include "exec/pipeline/scan/morsel.h"
#include "exprs/runtime_filter_bank.h"
#include "gen_cpp/InternalService_types.h"
#include "gen_cpp/PlanNodes_types.h"
#include "runtime/runtime_state.h"
#include "storage/chunk_helper.h"

namespace starrocks {

class ExprContext;
class ConnectorScanNode;
class RuntimeFilterProbeCollector;

namespace connector {

// DataSource defines how to read data from a single scan range.
// currently scan range is defined by `TScanRange`, I think it's better defined by DataSourceProvider.
// DataSourceProvider can split a single scan range further into multiple smaller & customized scan ranges.
// In that way fine granularity can be supported, multiple `DataSoruce`s can read data from a single scan range.
class DataSource {
public:
    virtual ~DataSource() = default;
    virtual std::string name() const = 0;
    virtual Status open(RuntimeState* state) { return Status::OK(); }
    virtual void close(RuntimeState* state) {}
    virtual Status get_next(RuntimeState* state, ChunkPtr* chunk) { return Status::OK(); }
    virtual bool has_any_predicate() const { return _has_any_predicate; }

    // how many rows read from storage
    virtual int64_t raw_rows_read() const = 0;
    // how many rows returned after filtering.
    virtual int64_t num_rows_read() const = 0;
    // how many bytes read from external
    virtual int64_t num_bytes_read() const = 0;
    // CPU time of this data source
    virtual int64_t cpu_time_spent() const = 0;
    // IO time of this data source
    virtual int64_t io_time_spent() const { return 0; }
    virtual bool can_estimate_mem_usage() const { return false; }
    virtual int64_t estimated_mem_usage() const { return 0; }

    // following fields are set by framework
    // 1. runtime profile: any metrics you want to record
    // 2. predicates: predicates in SQL query(possibly including IN filters generated by broadcast join)
    // 3. runtime filters: local & global runtime filters(or dynamic filters)
    // 4. read limit: for case like `select xxxx from table limit 10`.
    static const std::string PROFILE_NAME;
    void set_runtime_profile(RuntimeProfile* parent) {
        _runtime_profile = parent->create_child(PROFILE_NAME);
        _runtime_profile->add_info_string("DataSourceType", name());
    }
    void set_predicates(const std::vector<ExprContext*>& predicates) { _conjunct_ctxs = predicates; }
    void set_runtime_filters(RuntimeFilterProbeCollector* runtime_filters) { _runtime_filters = runtime_filters; }
    void set_read_limit(const uint64_t limit) { _read_limit = limit; }
    void set_split_context(pipeline::ScanSplitContext* split_context) { _split_context = split_context; }
    Status parse_runtime_filters(RuntimeState* state);
    void update_has_any_predicate();
    // Called frequently, don't do heavy work
    virtual const std::string get_custom_coredump_msg() const { return ""; }
    virtual void get_split_tasks(std::vector<pipeline::ScanSplitContextPtr>* split_tasks) {}

    struct Profile {
        int mem_alloc_failed_count;
    };
    void update_profile(const Profile& profile);
    void set_morsel(pipeline::ScanMorsel* morsel) { _morsel = morsel; }

    void set_driver_sequence(size_t driver_sequence) {
        runtime_bloom_filter_eval_context.driver_sequence = driver_sequence;
    }

protected:
    int64_t _read_limit = -1; // no limit
    bool _has_any_predicate = false;
    std::vector<ExprContext*> _conjunct_ctxs;
    RuntimeFilterProbeCollector* _runtime_filters = nullptr;
    RuntimeBloomFilterEvalContext runtime_bloom_filter_eval_context;
    RuntimeProfile* _runtime_profile = nullptr;
    TupleDescriptor* _tuple_desc = nullptr;
    pipeline::ScanSplitContext* _split_context = nullptr;

    virtual void _init_chunk(ChunkPtr* chunk, size_t n) { *chunk = ChunkHelper::new_chunk(*_tuple_desc, n); }
    pipeline::ScanMorsel* _morsel = nullptr;
};

class StreamDataSource : public DataSource {
public:
    virtual Status set_offset(int64_t table_version, int64_t changelog_id) = 0;
    virtual Status reset_status() = 0;

    // how many rows returned in the current epoch.
    virtual int64_t num_rows_read_in_epoch() const = 0;

    // CPU time of this data source in the current epoch.
    virtual int64_t cpu_time_spent_in_epoch() const = 0;
};

using DataSourcePtr = std::unique_ptr<DataSource>;

class DataSourceProvider {
public:
    static constexpr int64_t MIN_DATA_SOURCE_MEM_BYTES = 16 * 1024 * 1024;  // 16MB
    static constexpr int64_t MAX_DATA_SOURCE_MEM_BYTES = 256 * 1024 * 1024; // 256MB
    static constexpr int64_t PER_FIELD_MEM_BYTES = 4 * 1024 * 1024;         // 4MB

    virtual ~DataSourceProvider() = default;

    // First version we use TScanRange to define scan range
    // Later version we could use user-defined data.
    virtual DataSourcePtr create_data_source(const TScanRange& scan_range) = 0;
    // virtual DataSourcePtr create_data_source(const std::string& scan_range_spec)  = 0;

    // non-pipeline APIs
    Status prepare(RuntimeState* state) { return Status::OK(); }
    Status open(RuntimeState* state) { return Status::OK(); }
    void close(RuntimeState* state) {}

    // For some data source does not support scan ranges, dop is limited to 1,
    // and that will limit upper operators. And the solution is to insert a local exchange operator to fanout
    // and let upper operators have better parallelism.
    virtual bool insert_local_exchange_operator() const { return false; }

    // If this data source accept empty scan ranges, because for some data source there is no concept of scan ranges
    // such as MySQL/JDBC, so `accept_empty_scan_ranges` is false, and most in most cases, these data source(MySQL/JDBC)
    // the method `insert_local_exchange_operator` is true also.
    virtual bool accept_empty_scan_ranges() const { return true; }

    virtual bool stream_data_source() const { return false; }

    virtual Status init(ObjectPool* pool, RuntimeState* state) { return Status::OK(); }

    const std::vector<ExprContext*>& partition_exprs() const { return _partition_exprs; }

    virtual const TupleDescriptor* tuple_descriptor(RuntimeState* state) const = 0;

    virtual bool always_shared_scan() const { return true; }

    virtual void peek_scan_ranges(const std::vector<TScanRangeParams>& scan_ranges) {}

    virtual void default_data_source_mem_bytes(int64_t* min_value, int64_t* max_value) {
        *min_value = MIN_DATA_SOURCE_MEM_BYTES;
        *max_value = MAX_DATA_SOURCE_MEM_BYTES;
    }

    virtual StatusOr<pipeline::MorselQueuePtr> convert_scan_range_to_morsel_queue(
            const std::vector<TScanRangeParams>& scan_ranges, int node_id, int32_t pipeline_dop,
            bool enable_tablet_internal_parallel, TTabletInternalParallelMode::type tablet_internal_parallel_mode,
            size_t num_total_scan_ranges);

    bool could_split() const { return _could_split; }

    bool could_split_physically() const { return _could_split_physically; }

    int64_t get_splitted_scan_rows() const { return splitted_scan_rows; }
    int64_t get_scan_dop() const { return scan_dop; }

protected:
    std::vector<ExprContext*> _partition_exprs;
    bool _could_split = false;
    bool _could_split_physically = false;
    int64_t splitted_scan_rows = 0;
    int64_t scan_dop = 0;
};
using DataSourceProviderPtr = std::unique_ptr<DataSourceProvider>;

enum ConnectorType {
    HIVE = 0,
    ES = 1,
    JDBC = 2,
    MYSQL = 3,
    FILE = 4,
    LAKE = 5,
    BINLOG = 6,
    ICEBERG = 7,
};

class Connector {
public:
    // supported connectors.
    static const std::string HIVE;
    static const std::string ES;
    static const std::string JDBC;
    static const std::string MYSQL;
    static const std::string FILE;
    static const std::string LAKE;
    static const std::string BINLOG;
    static const std::string ICEBERG;

    virtual ~Connector() = default;
    // First version we use TPlanNode to construct data source provider.
    // Later version we could use user-defined data.

    virtual DataSourceProviderPtr create_data_source_provider(ConnectorScanNode* scan_node,
                                                              const TPlanNode& plan_node) const {
        CHECK(false) << connector_type() << " connector does not implement chunk source yet";
        __builtin_unreachable();
    }

    // virtual DataSourceProviderPtr create_data_source_provider(ConnectorScanNode* scan_node,
    //                                                         const std::string& table_handle) const;

    virtual std::unique_ptr<ConnectorChunkSinkProvider> create_data_sink_provider() const {
        CHECK(false) << connector_type() << " connector does not implement chunk sink yet";
        __builtin_unreachable();
    }

    virtual ConnectorType connector_type() const = 0;
};

class ConnectorManager {
public:
    static ConnectorManager* default_instance();
    const Connector* get(const std::string& name);
    void put(const std::string& name, std::unique_ptr<Connector> connector);

private:
    std::unordered_map<std::string, std::unique_ptr<Connector>> _connectors;
};

} // namespace connector
} // namespace starrocks
