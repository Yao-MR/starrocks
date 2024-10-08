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

#include "storage/lake/rowset.h"

#include <future>

#include "storage/chunk_helper.h"
#include "storage/delete_predicates.h"
#include "storage/lake/column_mode_partial_update_handler.h"
#include "storage/lake/tablet.h"
#include "storage/lake/update_manager.h"
#include "storage/projection_iterator.h"
#include "storage/rowset/rowid_range_option.h"
#include "storage/rowset/rowset_options.h"
#include "storage/rowset/segment.h"
#include "storage/rowset/segment_options.h"
#include "storage/rowset/short_key_range_option.h"
#include "storage/tablet_schema_map.h"
#include "storage/union_iterator.h"

namespace starrocks::lake {

Rowset::Rowset(TabletManager* tablet_mgr, int64_t tablet_id, const RowsetMetadataPB* metadata, int index,
               TabletSchemaPtr tablet_schema)
        : _tablet_mgr(tablet_mgr),
          _tablet_id(tablet_id),
          _metadata(metadata),
          _index(index),
          _tablet_schema(std::move(tablet_schema)) {}

Rowset::Rowset(TabletManager* tablet_mgr, TabletMetadataPtr tablet_metadata, int rowset_index)
        : _tablet_mgr(tablet_mgr),
          _tablet_id(tablet_metadata->id()),
          _metadata(&tablet_metadata->rowsets(rowset_index)),
          _index(rowset_index),
          _tablet_metadata(std::move(tablet_metadata)) {
    auto rowset_id = _tablet_metadata->rowsets(rowset_index).id();
    if (_tablet_metadata->rowset_to_schema().empty() ||
        _tablet_metadata->rowset_to_schema().find(rowset_id) == _tablet_metadata->rowset_to_schema().end()) {
        _tablet_schema = GlobalTabletSchemaMap::Instance()->emplace(_tablet_metadata->schema()).first;
    } else {
        auto schema_id = _tablet_metadata->rowset_to_schema().at(rowset_id);
        CHECK(_tablet_metadata->historical_schemas().count(schema_id) > 0);
        _tablet_schema =
                GlobalTabletSchemaMap::Instance()->emplace(_tablet_metadata->historical_schemas().at(schema_id)).first;
    }
}

Rowset::~Rowset() {
    if (_tablet_metadata) {
        DCHECK_LT(_index, _tablet_metadata->rowsets_size())
                << "tablet metadata been modified before rowset been destroyed";
        DCHECK_EQ(_metadata, &_tablet_metadata->rowsets(_index))
                << "tablet metadata been modified during rowset been destroyed";
    }
}

StatusOr<std::vector<ChunkIteratorPtr>> Rowset::read(const Schema& schema, const RowsetReadOptions& options) {
    auto root_loc = _tablet_mgr->tablet_root_location(tablet_id());
    SegmentReadOptions seg_options;
    ASSIGN_OR_RETURN(seg_options.fs, FileSystem::CreateSharedFromString(root_loc));
    seg_options.stats = options.stats;
    seg_options.ranges = options.ranges;
    seg_options.pred_tree = options.pred_tree;
    seg_options.pred_tree_for_zone_map = options.pred_tree_for_zone_map;
    seg_options.use_page_cache = options.use_page_cache;
    seg_options.profile = options.profile;
    seg_options.reader_type = options.reader_type;
    seg_options.chunk_size = options.chunk_size;
    seg_options.global_dictmaps = options.global_dictmaps;
    seg_options.unused_output_column_ids = options.unused_output_column_ids;
    seg_options.runtime_range_pruner = options.runtime_range_pruner;
    seg_options.tablet_schema = options.tablet_schema;
    seg_options.lake_io_opts = options.lake_io_opts;
    seg_options.asc_hint = options.asc_hint;
    seg_options.column_access_paths = options.column_access_paths;
    if (options.is_primary_keys) {
        seg_options.is_primary_keys = true;
        seg_options.delvec_loader = std::make_shared<LakeDelvecLoader>(_tablet_mgr->update_mgr(), nullptr,
                                                                       seg_options.lake_io_opts.fill_data_cache);
        seg_options.dcg_loader = std::make_shared<LakeDeltaColumnGroupLoader>(_tablet_metadata);
        seg_options.version = options.version;
        seg_options.tablet_id = tablet_id();
        seg_options.rowset_id = metadata().id();
    }
    if (options.delete_predicates != nullptr) {
        seg_options.delete_predicates = options.delete_predicates->get_predicates(_index);
    }

    if (options.short_key_ranges_option != nullptr) { // logical split.
        seg_options.short_key_ranges = options.short_key_ranges_option->short_key_ranges;
    }

    std::unique_ptr<Schema> segment_schema_guard;
    auto* segment_schema = const_cast<Schema*>(&schema);
    // Append the columns with delete condition to segment schema.
    std::set<ColumnId> delete_columns;
    seg_options.delete_predicates.get_column_ids(&delete_columns);
    for (ColumnId cid : delete_columns) {
        const TabletColumn& col = options.tablet_schema->column(cid);
        if (segment_schema->get_field_by_name(std::string(col.name())) != nullptr) {
            continue;
        }
        // copy on write
        if (segment_schema == &schema) {
            segment_schema = new Schema(schema);
            segment_schema_guard.reset(segment_schema);
        }
        auto f = ChunkHelper::convert_field(cid, col);
        segment_schema->append(std::make_shared<Field>(std::move(f)));
    }

    std::vector<ChunkIteratorPtr> segment_iterators;
    segment_iterators.reserve(num_segments());
    if (options.stats) {
        options.stats->segments_read_count += num_segments();
    }

    std::vector<SegmentPtr> segments;
    RETURN_IF_ERROR(load_segments(&segments, options.lake_io_opts.fill_data_cache, options.lake_io_opts.buffer_size));
    for (auto& seg_ptr : segments) {
        if (seg_ptr->num_rows() == 0) {
            continue;
        }

        if (options.rowid_range_option != nullptr) { // physical split.
            auto [rowid_range, is_first_split_of_segment] =
                    options.rowid_range_option->get_segment_rowid_range(this, seg_ptr.get());
            if (rowid_range == nullptr) {
                continue;
            }
            seg_options.rowid_range_option = std::move(rowid_range);
            seg_options.is_first_split_of_segment = is_first_split_of_segment;
        } else if (options.short_key_ranges_option != nullptr) { // logical split.
            seg_options.is_first_split_of_segment = options.short_key_ranges_option->is_first_split_of_tablet;
        } else {
            seg_options.is_first_split_of_segment = true;
        }

        auto res = seg_ptr->new_iterator(*segment_schema, seg_options);
        if (res.status().is_end_of_file()) {
            continue;
        }
        if (!res.ok()) {
            return res.status();
        }
        if (segment_schema != &schema) {
            segment_iterators.emplace_back(new_projection_iterator(schema, std::move(res).value()));
        } else {
            segment_iterators.emplace_back(std::move(res).value());
        }
    }
    if (segment_iterators.size() > 1 && !is_overlapped()) {
        // union non-overlapped segment iterators
        auto iter = new_union_iterator(std::move(segment_iterators));
        return std::vector<ChunkIteratorPtr>{iter};
    } else {
        return segment_iterators;
    }
}

StatusOr<size_t> Rowset::get_read_iterator_num() {
    std::vector<SegmentPtr> segments;
    RETURN_IF_ERROR(load_segments(&segments, false));

    size_t segment_num = 0;
    for (auto& seg_ptr : segments) {
        if (seg_ptr->num_rows() == 0) {
            continue;
        }
        ++segment_num;
    }

    if (segment_num > 1 && !is_overlapped()) {
        return 1;
    } else {
        return segment_num;
    }
}

StatusOr<std::vector<ChunkIteratorPtr>> Rowset::get_each_segment_iterator(const Schema& schema, bool file_data_cache,
                                                                          OlapReaderStatistics* stats) {
    std::vector<SegmentPtr> segments;
    RETURN_IF_ERROR(load_segments(&segments, file_data_cache));
    std::vector<ChunkIteratorPtr> seg_iterators;
    seg_iterators.reserve(segments.size());
    auto root_loc = _tablet_mgr->tablet_root_location(tablet_id());
    SegmentReadOptions seg_options;
    ASSIGN_OR_RETURN(seg_options.fs, FileSystem::CreateSharedFromString(root_loc));
    seg_options.stats = stats;
    for (auto& seg_ptr : segments) {
        auto res = seg_ptr->new_iterator(schema, seg_options);
        if (res.status().is_end_of_file()) {
            continue;
        }
        if (!res.ok()) {
            return res.status();
        }
        seg_iterators.push_back(std::move(res).value());
    }
    return seg_iterators;
}

StatusOr<std::vector<ChunkIteratorPtr>> Rowset::get_each_segment_iterator_with_delvec(const Schema& schema,
                                                                                      int64_t version,
                                                                                      const MetaFileBuilder* builder,
                                                                                      OlapReaderStatistics* stats) {
    std::vector<SegmentPtr> segments;
    RETURN_IF_ERROR(load_segments(&segments, false));
    auto root_loc = _tablet_mgr->tablet_root_location(tablet_id());
    std::vector<ChunkIteratorPtr> seg_iterators;
    seg_iterators.reserve(segments.size());
    SegmentReadOptions seg_options;
    ASSIGN_OR_RETURN(seg_options.fs, FileSystem::CreateSharedFromString(root_loc));
    seg_options.stats = stats;
    seg_options.is_primary_keys = true;
    seg_options.delvec_loader =
            std::make_shared<LakeDelvecLoader>(_tablet_mgr->update_mgr(), builder, true /*fill cache*/);
    seg_options.dcg_loader = std::make_shared<LakeDeltaColumnGroupLoader>(_tablet_metadata);
    seg_options.version = version;
    seg_options.tablet_id = tablet_id();
    seg_options.rowset_id = metadata().id();
    for (auto& seg_ptr : segments) {
        auto res = seg_ptr->new_iterator(schema, seg_options);
        if (res.status().is_end_of_file()) {
            continue;
        }
        if (!res.ok()) {
            return res.status();
        }
        seg_iterators.push_back(std::move(res).value());
    }
    return seg_iterators;
}

RowsetId Rowset::rowset_id() const {
    RowsetId rowset_id;
    rowset_id.init(id());
    return rowset_id;
}

std::vector<SegmentSharedPtr> Rowset::get_segments() {
    if (!_segments.empty()) {
        return _segments;
    }

    auto segments_or = segments(true);
    if (!segments_or.ok()) {
        return {};
    }
    _segments = std::move(segments_or.value());
    return _segments;
}

StatusOr<std::vector<SegmentPtr>> Rowset::segments(bool fill_cache) {
    LakeIOOptions lake_io_opts{.fill_data_cache = fill_cache};
    return segments(lake_io_opts, fill_cache);
}

StatusOr<std::vector<SegmentPtr>> Rowset::segments(const LakeIOOptions& lake_io_opts, bool fill_metadata_cache) {
    std::vector<SegmentPtr> segments;
    RETURN_IF_ERROR(load_segments(&segments, lake_io_opts, fill_metadata_cache));
    return segments;
}

Status Rowset::load_segments(std::vector<SegmentPtr>* segments, bool fill_cache, int64_t buffer_size) {
    LakeIOOptions lake_io_opts{.fill_data_cache = fill_cache, .buffer_size = buffer_size};
    return load_segments(segments, lake_io_opts, fill_cache);
}

Status Rowset::load_segments(std::vector<SegmentPtr>* segments, const LakeIOOptions& lake_io_opts,
                             bool fill_metadata_cache) {
#ifndef BE_TEST
    RETURN_IF_ERROR(tls_thread_status.mem_tracker()->check_mem_limit("LoadSegments"));
#endif

    segments->reserve(segments->size() + metadata().segments_size());

    size_t footer_size_hint = 16 * 1024;
    uint32_t seg_id = 0;
    bool ignore_lost_segment = config::experimental_lake_ignore_lost_segment;

    // RowsetMetaData upgrade from old version may not have the field of segment_size
    auto segment_size_size = metadata().segment_size_size();
    auto segment_file_size = metadata().segments_size();
    bool has_segment_size = segment_size_size == segment_file_size;
    LOG_IF(ERROR, segment_size_size > 0 && segment_size_size != segment_file_size)
            << "segment_size size != segment file size, tablet: " << _tablet_id << ", rowset: " << metadata().id()
            << ", segment file size: " << segment_file_size << ", segment_size size: " << segment_size_size;

    const auto& files_to_size = metadata().segment_size();
    int index = 0;

    std::vector<std::future<std::pair<StatusOr<SegmentPtr>, std::string>>> segment_futures;
    auto check_status = [&](StatusOr<SegmentPtr>& segment_or, const std::string& seg_name) -> Status {
        if (segment_or.ok()) {
            segments->emplace_back(std::move(segment_or.value()));
        } else if (segment_or.status().is_not_found() && ignore_lost_segment) {
            LOG(WARNING) << "Ignored lost segment " << seg_name;
        } else {
            return segment_or.status();
        }
        return Status::OK();
    };

    for (const auto& seg_name : metadata().segments()) {
        auto segment_path = _tablet_mgr->segment_location(tablet_id(), seg_name);
        auto segment_info = FileInfo{.path = segment_path};
        if (LIKELY(has_segment_size)) {
            segment_info.size = files_to_size.Get(index);
        }
        auto segment_encryption_metas_size = metadata().segment_encryption_metas_size();
        if (segment_encryption_metas_size > 0) {
            if (index >= segment_encryption_metas_size) {
                string msg = fmt::format("tablet:{} rowset:{} index:{} >= segment_encryption_metas size:{}", _tablet_id,
                                         metadata().id(), index, segment_encryption_metas_size);
                LOG(ERROR) << msg;
                return Status::Corruption(msg);
            }
            segment_info.encryption_meta = metadata().segment_encryption_metas(index);
        }
        index++;

        if (config::enable_load_segment_parallel) {
            auto task = std::make_shared<std::packaged_task<std::pair<StatusOr<SegmentPtr>, std::string>()>>([=]() {
                auto result = _tablet_mgr->load_segment(segment_info, seg_id, lake_io_opts, fill_metadata_cache,
                                                        _tablet_schema);
                return std::make_pair(std::move(result), seg_name);
            });

            auto packaged_func = [task]() { (*task)(); };
            if (auto st = ExecEnv::GetInstance()->load_segment_thread_pool()->submit_func(std::move(packaged_func));
                !st.ok()) {
                // try load segment serially
                LOG(WARNING) << "sumbit_func failed: " << st.code_as_string()
                             << ", try to load segment serially, seg_id: " << seg_id;
                auto segment_or = _tablet_mgr->load_segment(segment_info, seg_id, &footer_size_hint, lake_io_opts,
                                                            fill_metadata_cache, _tablet_schema);
                if (auto status = check_status(segment_or, seg_name); !status.ok()) {
                    return status;
                }
            }
            seg_id++;
            segment_futures.push_back(task->get_future());
        } else {
            auto segment_or = _tablet_mgr->load_segment(segment_info, seg_id++, &footer_size_hint, lake_io_opts,
                                                        fill_metadata_cache, _tablet_schema);
            if (auto status = check_status(segment_or, seg_name); !status.ok()) {
                return status;
            }
        }
    }

    for (auto& fut : segment_futures) {
        auto result_pair = fut.get();
        auto segment_or = result_pair.first;
        if (auto status = check_status(segment_or, result_pair.second); !status.ok()) {
            return status;
        }
    }
    return Status::OK();
}

} // namespace starrocks::lake
