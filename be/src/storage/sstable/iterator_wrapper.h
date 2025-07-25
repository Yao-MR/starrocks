// Copyright (c) 2011 The LevelDB Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license.
// (https://developers.google.com/open-source/licenses/bsd)

#include "storage/sstable/iterator.h"

namespace starrocks {
class Slice;
namespace sstable {

// A internal wrapper class with an interface similar to Iterator that
// caches the valid() and key() results for an underlying iterator.
// This can help avoid virtual function calls and also gives better
// cache locality.
class IteratorWrapper {
public:
    IteratorWrapper() : iter_(nullptr), valid_(false) {}
    explicit IteratorWrapper(Iterator* iter) : iter_(nullptr) { Set(iter); }
    ~IteratorWrapper() { delete iter_; }
    Iterator* iter() const { return iter_; }

    // Takes ownership of "iter" and will delete it when destroyed, or
    // when Set() is invoked again.
    void Set(Iterator* iter) {
        delete iter_;
        iter_ = iter;
        if (iter_ == nullptr) {
            valid_ = false;
        } else {
            Update();
        }
    }

    // Iterator interface methods
    bool Valid() const { return valid_; }
    Slice key() const {
        assert(Valid());
        return key_;
    }
    Slice value() const {
        assert(Valid());
        return iter_->value();
    }
    // Methods below require iter() != nullptr
    Status status() const {
        assert(iter_);
        return iter_->status();
    }
    uint64_t max_rss_rowid() const {
        assert(iter_);
        return iter_->max_rss_rowid();
    }
    SstablePredicateSPtr predicate() const {
        assert(iter_);
        return iter_->predicate();
    }
    void Next() {
        assert(iter_);
        iter_->Next();
        Update();
    }
    void Prev() {
        assert(iter_);
        iter_->Prev();
        Update();
    }
    void Seek(const Slice& k) {
        assert(iter_);
        iter_->Seek(k);
        Update();
    }
    void SeekToFirst() {
        assert(iter_);
        iter_->SeekToFirst();
        Update();
    }
    void SeekToLast() {
        assert(iter_);
        iter_->SeekToLast();
        Update();
    }

private:
    void Update() {
        valid_ = iter_->Valid();
        if (valid_) {
            key_ = iter_->key();
        }
    }

    Iterator* iter_;
    bool valid_;
    Slice key_;
};

} // namespace sstable
} // namespace starrocks
