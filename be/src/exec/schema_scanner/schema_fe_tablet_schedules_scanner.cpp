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

#include "exec/schema_scanner/schema_fe_tablet_schedules_scanner.h"

#include "exec/schema_scanner/schema_helper.h"
#include "gutil/strings/substitute.h"
#include "storage/tablet.h"
#include "types/logical_type.h"

namespace starrocks {

SchemaScanner::ColumnDesc SchemaFeTabletSchedulesScanner::_s_columns[] = {
        {"TABLE_ID", TypeDescriptor::from_logical_type(TYPE_BIGINT), sizeof(int64_t), false},
        {"PARTITION_ID", TypeDescriptor::from_logical_type(TYPE_BIGINT), sizeof(int64_t), false},
        {"TABLET_ID", TypeDescriptor::from_logical_type(TYPE_BIGINT), sizeof(int64_t), false},
        {"TYPE", TypeDescriptor::create_varchar_type(sizeof(Slice)), sizeof(Slice), false},
        {"PRIORITY", TypeDescriptor::create_varchar_type(sizeof(Slice)), sizeof(Slice), false},
        {"STATE", TypeDescriptor::create_varchar_type(sizeof(Slice)), sizeof(Slice), false},
        {"TABLET_STATUS", TypeDescriptor::create_varchar_type(sizeof(Slice)), sizeof(Slice), false},
        {"CREATE_TIME", TypeDescriptor::from_logical_type(TYPE_DATETIME), sizeof(DateTimeValue), true},
        {"SCHEDULE_TIME", TypeDescriptor::from_logical_type(TYPE_DATETIME), sizeof(DateTimeValue), true},
        {"FINISH_TIME", TypeDescriptor::from_logical_type(TYPE_DATETIME), sizeof(DateTimeValue), true},
        {"CLONE_SRC", TypeDescriptor::from_logical_type(TYPE_BIGINT), sizeof(int64_t), false},
        {"CLONE_DEST", TypeDescriptor::from_logical_type(TYPE_BIGINT), sizeof(int64_t), false},
        {"CLONE_BYTES", TypeDescriptor::from_logical_type(TYPE_BIGINT), sizeof(int64_t), false},
        {"CLONE_DURATION", TypeDescriptor::from_logical_type(TYPE_DOUBLE), sizeof(double), false},
        {"MSG", TypeDescriptor::create_varchar_type(sizeof(Slice)), sizeof(Slice), false},
};

SchemaFeTabletSchedulesScanner::SchemaFeTabletSchedulesScanner()
        : SchemaScanner(_s_columns, sizeof(_s_columns) / sizeof(SchemaScanner::ColumnDesc)) {}

SchemaFeTabletSchedulesScanner::~SchemaFeTabletSchedulesScanner() = default;

Status SchemaFeTabletSchedulesScanner::start(RuntimeState* state) {
    _cur_idx = 0;
    TGetTabletScheduleRequest request;
    TGetTabletScheduleResponse response;
    if (_param->table_id != -1) {
        request.__set_table_id(_param->table_id);
    }
    if (_param->partition_id != -1) {
        request.__set_partition_id(_param->partition_id);
    }
    if (_param->tablet_id != -1) {
        request.__set_tablet_id(_param->tablet_id);
    }
    if (_param->type != nullptr) {
        request.__set_type(*_param->type);
    }
    if (_param->state != nullptr) {
        request.__set_state(*_param->state);
    }
    if (_param->limit > 0) {
        request.__set_limit(_param->limit);
    }
    if (nullptr != _param->current_user_ident) {
        request.__set_current_user_ident(*(_param->current_user_ident));
    }

    RETURN_IF_ERROR(SchemaScanner::init_schema_scanner_state(state));
    RETURN_IF_ERROR(SchemaHelper::get_tablet_schedules(_ss_state, request, &response));
    _infos.swap(response.tablet_schedules);
    return SchemaScanner::start(state);
}

Status SchemaFeTabletSchedulesScanner::TEST_start(RuntimeState* state, const std::vector<TTabletSchedule>& infos) {
    _cur_idx = 0;
    _infos = infos;
    RETURN_IF_ERROR(SchemaScanner::init_schema_scanner_state(state));
    return SchemaScanner::start(state);
}

Status SchemaFeTabletSchedulesScanner::fill_chunk(ChunkPtr* chunk) {
    const auto& slot_id_to_index_map = (*chunk)->get_slot_id_to_index_map();
    auto end = _cur_idx + 1;
    for (; _cur_idx < end; _cur_idx++) {
        auto& info = _infos[_cur_idx];
        for (const auto& [slot_id, index] : slot_id_to_index_map) {
            if (slot_id < 1 || slot_id > 15) {
                return Status::InternalError(strings::Substitute("invalid slot id:$0", slot_id));
            }
            ColumnPtr column = (*chunk)->get_column_by_slot_id(slot_id);
            switch (slot_id) {
            case 1: {
                fill_column_with_slot<TYPE_BIGINT>(column.get(), (void*)&info.table_id);
                break;
            }
            case 2: {
                fill_column_with_slot<TYPE_BIGINT>(column.get(), (void*)&info.partition_id);
                break;
            }
            case 3: {
                fill_column_with_slot<TYPE_BIGINT>(column.get(), (void*)&info.tablet_id);
                break;
            }
            case 4: {
                Slice v = Slice(info.type);
                fill_column_with_slot<TYPE_VARCHAR>(column.get(), (void*)&v);
                break;
            }
            case 5: {
                Slice v = Slice(info.priority);
                fill_column_with_slot<TYPE_VARCHAR>(column.get(), (void*)&v);
                break;
            }
            case 6: {
                Slice v = Slice(info.state);
                fill_column_with_slot<TYPE_VARCHAR>(column.get(), (void*)&v);
                break;
            }
            case 7: {
                Slice v = Slice(info.tablet_status);
                fill_column_with_slot<TYPE_VARCHAR>(column.get(), (void*)&v);
                break;
            }
            case 8: {
                if (info.create_time > 0) {
                    DateTimeValue v;
                    v.from_unixtime(static_cast<int64_t>(info.create_time), _runtime_state->timezone_obj());
                    fill_column_with_slot<TYPE_DATETIME>(column.get(), (void*)&v);
                } else {
                    down_cast<NullableColumn*>(column.get())->append_nulls(1);
                }
                break;
            }
            case 9: {
                if (info.schedule_time > 0) {
                    DateTimeValue v;
                    v.from_unixtime(static_cast<int64_t>(info.schedule_time), _runtime_state->timezone_obj());
                    fill_column_with_slot<TYPE_DATETIME>(column.get(), (void*)&v);
                } else {
                    down_cast<NullableColumn*>(column.get())->append_nulls(1);
                }
                break;
            }
            case 10: {
                if (info.finish_time > 0) {
                    DateTimeValue v;
                    v.from_unixtime(static_cast<int64_t>(info.finish_time), _runtime_state->timezone_obj());
                    fill_column_with_slot<TYPE_DATETIME>(column.get(), (void*)&v);
                } else {
                    down_cast<NullableColumn*>(column.get())->append_nulls(1);
                }
                break;
            }
            case 11: {
                fill_column_with_slot<TYPE_BIGINT>(column.get(), (void*)&info.clone_src);
                break;
            }
            case 12: {
                fill_column_with_slot<TYPE_BIGINT>(column.get(), (void*)&info.clone_dest);
                break;
            }
            case 13: {
                fill_column_with_slot<TYPE_BIGINT>(column.get(), (void*)&info.clone_bytes);
                break;
            }
            case 14: {
                fill_column_with_slot<TYPE_DOUBLE>(column.get(), (void*)&info.clone_duration);
                break;
            }
            case 15: {
                Slice v = Slice(info.error_msg);
                fill_column_with_slot<TYPE_VARCHAR>(column.get(), (void*)&v);
                break;
            }
            default:
                break;
            }
        }
    }
    return Status::OK();
}

Status SchemaFeTabletSchedulesScanner::get_next(ChunkPtr* chunk, bool* eos) {
    if (!_is_init) {
        return Status::InternalError("call this before initial.");
    }
    if (_cur_idx >= _infos.size()) {
        *eos = true;
        return Status::OK();
    }
    if (nullptr == chunk || nullptr == eos) {
        return Status::InternalError("invalid parameter.");
    }
    *eos = false;
    return fill_chunk(chunk);
}

} // namespace starrocks
