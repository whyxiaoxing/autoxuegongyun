// ocr_filter.cpp
// CPython C++ extension: locate a target phrase in Tesseract TSV output and
// return the matched OCR text plus its bounding-box center.
// C++20, no `auto`, extern "C" module entry.

#define PY_SSIZE_T_CLEAN
#include <Python.h>

#include <algorithm>
#include <atomic>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <mutex>
#include <new>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

#ifdef _WIN32
#include <process.h>
#define OCR_GETPID _getpid
#else
#include <unistd.h>
#define OCR_GETPID getpid
#endif

namespace ocr {

enum class TsvColumn : std::size_t {
    Level = 0,
    Page = 1,
    Block = 2,
    Para = 3,
    Line = 4,
    Word = 5,
    Left = 6,
    Top = 7,
    Width = 8,
    Height = 9,
    Confidence = 10
};

static const std::size_t kColumnCount = 11;
static const std::size_t kInitialCapacity = 64;

// find() tuning ------------------------------------------------------------
static const std::size_t kMaxPatternLength = 64;
static const int kThreadCount = 4;
static const std::size_t kParallelThresholdBytes = 1024;
static const std::uint32_t kKeyTwoByteTag = 0x02000000u;
static const std::uint32_t kKeyThreeByteTag = 0x03000000u;
static const std::uint32_t kKeyFourByteTag = 0x04000000u;

static std::size_t ColumnIndex(TsvColumn column) {
    return static_cast<std::size_t>(column);
}

static std::int32_t ParseInt(const char* field_start) {
    return static_cast<std::int32_t>(std::strtol(field_start, nullptr, 10));
}

// ---------------------------------------------------------------------------
// Fixed-length UTF-8 keys, confusion recall, line-local candidates, resident
// worker pool, single-threaded chain combination.
// ---------------------------------------------------------------------------

// One decoded character. bytes/byte_length alias the caller's TSV buffer.
struct CharRecord {
    std::uint32_t key;
    std::int32_t left;
    std::int32_t top;
    std::int32_t width;
    std::int32_t height;
    const char* bytes;
    std::uint32_t byte_length;
};

struct LineChars {
    std::vector<CharRecord> chars;
};

struct Candidate {
    const LineChars* line;
    std::int32_t left;
    std::int32_t top;
    std::int32_t width;
    std::int32_t height;
    std::uint32_t line_id;
    std::uint32_t char_index;
};

struct LineSpan {
    std::size_t row_begin;
    std::size_t row_end;
    std::uint64_t text_bytes;
};

struct Match {
    std::string text;
    std::int32_t cx;
    std::int32_t cy;
};

struct WorkerScratch {
    std::vector<LineChars> lines;
    std::vector<std::vector<Candidate>> slots;

    WorkerScratch() : slots(kMaxPatternLength) {}
};

struct WordBox {
    std::int32_t page;
    std::int32_t block;
    std::int32_t para;
    std::int32_t line;
    std::int32_t word;
    std::int32_t left;
    std::int32_t top;
    std::int32_t width;
    std::int32_t height;
    const char* text;
    std::uint32_t text_length;
};

struct WordBoxArray {
    WordBox* data;
    std::size_t size;
    std::size_t capacity;

    WordBoxArray() : data(nullptr), size(0), capacity(0) {}
    ~WordBoxArray() { std::free(data); }
    WordBoxArray(const WordBoxArray&) = delete;
    WordBoxArray& operator=(const WordBoxArray&) = delete;
};

struct FindJob {
    const WordBox* words = nullptr;
    const LineSpan* lines = nullptr;
    std::size_t line_begin = 0;
    std::size_t line_end = 0;
    const std::vector<std::vector<std::uint32_t>>* variants = nullptr;
};

static std::size_t Utf8SequenceLength(unsigned char lead) {
    if (lead < 0x80u) {
        return 1;
    }
    if ((lead & 0xE0u) == 0xC0u) {
        return 2;
    }
    if ((lead & 0xF0u) == 0xE0u) {
        return 3;
    }
    if ((lead & 0xF8u) == 0xF0u) {
        return 4;
    }
    return 1;
}

// Fixed-length key: 3-byte CJK sequences pack into 24 bits, ASCII keeps its
// byte value, and 2/4-byte sequences get a length tag. Equality is exact, so
// the key is usable for both hashing and verification.
static std::uint32_t EncodeKey(const char* bytes, std::size_t length) {
    const unsigned char* raw = reinterpret_cast<const unsigned char*>(bytes);
    if (length == 1) {
        return static_cast<std::uint32_t>(raw[0]);
    }
    if (length == 2) {
        return kKeyTwoByteTag | (static_cast<std::uint32_t>(raw[0]) << 8) |
               static_cast<std::uint32_t>(raw[1]);
    }
    if (length == 3) {
        return kKeyThreeByteTag | (static_cast<std::uint32_t>(raw[0]) << 16) |
               (static_cast<std::uint32_t>(raw[1]) << 8) |
               static_cast<std::uint32_t>(raw[2]);
    }
    std::uint32_t hash = 2166136261u;
    for (std::size_t i = 0; i < length; ++i) {
        hash ^= static_cast<std::uint32_t>(raw[i]);
        hash *= 16777619u;
    }
    return kKeyFourByteTag | (hash & 0x00FFFFFFu);
}

static std::mutex& ConfusionMutex() {
    static std::mutex mutex;
    return mutex;
}

// Empty until the caller adds pairs with set_confusion(); no OCR confusions
// are hardcoded.
static std::unordered_map<std::uint32_t, std::vector<std::uint32_t>>& ConfusionTable() {
    static std::unordered_map<std::uint32_t, std::vector<std::uint32_t>> table;
    return table;
}

// Decode a pattern into fixed-length keys. GIL must be held (sets ValueError).
static bool DecodePatternKeys(const char* data, Py_ssize_t length,
                              std::vector<std::uint32_t>* keys) {
    keys->clear();
    Py_ssize_t offset = 0;
    while (offset < length) {
        std::size_t sequence = Utf8SequenceLength(
            static_cast<unsigned char>(data[offset]));
        if (offset + static_cast<Py_ssize_t>(sequence) > length) {
            sequence = 1;
        }
        if (keys->size() >= kMaxPatternLength) {
            PyErr_Format(PyExc_ValueError,
                         "pattern must be at most %zu characters",
                         kMaxPatternLength);
            return false;
        }
        keys->push_back(EncodeKey(data + offset, sequence));
        offset += static_cast<Py_ssize_t>(sequence);
    }
    return true;
}

static bool ReserveWordBoxes(WordBoxArray* array, std::size_t needed) {
    if (needed <= array->capacity) {
        return true;
    }
    std::size_t capacity = (array->capacity == 0) ? kInitialCapacity : array->capacity;
    while (capacity < needed) {
        capacity *= 2;
    }
    WordBox* resized = static_cast<WordBox*>(
        std::realloc(array->data, capacity * sizeof(WordBox)));
    if (resized == nullptr) {
        return false;
    }
    array->data = resized;
    array->capacity = capacity;
    return true;
}

// No Python API calls: safe to run with the GIL released.
static bool ParseWordBoxes(const char* data, Py_ssize_t data_length, float min_conf,
                           WordBoxArray* array) {
    const char* cursor = data;
    const char* end = data + data_length;

    const char* column_start[kColumnCount] = {nullptr};
    const char* column_end[kColumnCount] = {nullptr};

    while (cursor < end) {
        const char* newline = static_cast<const char*>(
            std::memchr(cursor, '\n', static_cast<std::size_t>(end - cursor)));
        const char* line_end = (newline != nullptr) ? newline : end;
        const char* text_end = line_end;
        if (text_end > cursor && text_end[-1] == '\r') {
            --text_end;
        }

        std::size_t column_count = 0;
        for (std::size_t c = 0; c < kColumnCount; ++c) {
            const char* field_start = (c == 0) ? cursor : (column_end[c - 1] + 1);
            if (field_start > text_end) {
                break;
            }
            const char* tab = static_cast<const char*>(
                std::memchr(field_start, '\t', static_cast<std::size_t>(text_end - field_start)));
            if (tab == nullptr) {
                break;
            }
            column_start[c] = field_start;
            column_end[c] = tab;
            column_count = c + 1;
        }

        if (column_count == kColumnCount) {
            const char* text_start = column_end[kColumnCount - 1] + 1;
            const Py_ssize_t text_length = static_cast<Py_ssize_t>(text_end - text_start);
            const char* level_start = column_start[ColumnIndex(TsvColumn::Level)];

            if (level_start[0] >= '0' && level_start[0] <= '9' && text_length > 0) {
                const std::int32_t level = ParseInt(level_start);
                const float confidence = std::strtof(
                    column_start[ColumnIndex(TsvColumn::Confidence)], nullptr) / 100.0f;

                if (level == 5 && confidence >= 0.0f && confidence >= min_conf) {
                    if (!ReserveWordBoxes(array, array->size + 1)) {
                        return false;
                    }
                    WordBox* record = &array->data[array->size];
                    record->page = ParseInt(column_start[ColumnIndex(TsvColumn::Page)]);
                    record->block = ParseInt(column_start[ColumnIndex(TsvColumn::Block)]);
                    record->para = ParseInt(column_start[ColumnIndex(TsvColumn::Para)]);
                    record->line = ParseInt(column_start[ColumnIndex(TsvColumn::Line)]);
                    record->word = ParseInt(column_start[ColumnIndex(TsvColumn::Word)]);
                    record->left = ParseInt(column_start[ColumnIndex(TsvColumn::Left)]);
                    record->top = ParseInt(column_start[ColumnIndex(TsvColumn::Top)]);
                    record->width = ParseInt(column_start[ColumnIndex(TsvColumn::Width)]);
                    record->height = ParseInt(column_start[ColumnIndex(TsvColumn::Height)]);
                    record->text = text_start;
                    record->text_length = static_cast<std::uint32_t>(text_length);
                    ++array->size;
                }
            }
        }

        if (newline == nullptr) {
            break;
        }
        cursor = newline + 1;
    }

    return true;
}

static bool SameLineBox(const WordBox& left, const WordBox& right) {
    return left.page == right.page && left.block == right.block &&
           left.para == right.para && left.line == right.line;
}

// Expand one line's words into per-character boxes, then sort by left.
static void BuildLineChars(const WordBox* words, const LineSpan& span, LineChars* line) {
    line->chars.clear();

    for (std::size_t row = span.row_begin; row < span.row_end; ++row) {
        const WordBox& word = words[row];

        std::uint32_t char_count = 0;
        std::uint32_t offset = 0;
        while (offset < word.text_length) {
            std::size_t sequence = Utf8SequenceLength(
                static_cast<unsigned char>(word.text[offset]));
            if (offset + sequence > word.text_length) {
                sequence = 1;
            }
            ++char_count;
            offset += static_cast<std::uint32_t>(sequence);
        }
        if (char_count == 0) {
            continue;
        }

        offset = 0;
        std::uint32_t index = 0;
        while (offset < word.text_length) {
            std::size_t sequence = Utf8SequenceLength(
                static_cast<unsigned char>(word.text[offset]));
            if (offset + sequence > word.text_length) {
                sequence = 1;
            }

            const std::int64_t left = static_cast<std::int64_t>(word.left) +
                static_cast<std::int64_t>(word.width) * static_cast<std::int64_t>(index) /
                    static_cast<std::int64_t>(char_count);
            const std::int64_t right = static_cast<std::int64_t>(word.left) +
                static_cast<std::int64_t>(word.width) *
                    static_cast<std::int64_t>(index + 1) /
                    static_cast<std::int64_t>(char_count);

            CharRecord record;
            record.key = EncodeKey(word.text + offset, sequence);
            record.left = static_cast<std::int32_t>(left);
            record.top = word.top;
            record.width = static_cast<std::int32_t>(right - left);
            record.height = word.height;
            record.bytes = word.text + offset;
            record.byte_length = static_cast<std::uint32_t>(sequence);
            line->chars.push_back(record);

            offset += static_cast<std::uint32_t>(sequence);
            ++index;
        }
    }

    // Stable insertion sort: OCR words arrive left-to-right in practice, so
    // this is near-linear and avoids std::stable_sort's allocation.
    for (std::size_t i = 1; i < line->chars.size(); ++i) {
        const CharRecord value = line->chars[i];
        std::size_t j = i;
        while (j > 0 && line->chars[j - 1].left > value.left) {
            line->chars[j] = line->chars[j - 1];
            --j;
        }
        line->chars[j] = value;
    }
}

static void RunFindJob(const FindJob& job, WorkerScratch* scratch) {
    const std::size_t line_count = job.line_end - job.line_begin;
    if (scratch->lines.size() < line_count) {
        scratch->lines.resize(line_count);
    }

    const std::size_t pattern_length = job.variants->size();
    for (std::size_t i = 0; i < pattern_length; ++i) {
        scratch->slots[i].clear();
    }

    for (std::size_t local = 0; local < line_count; ++local) {
        LineChars& line = scratch->lines[local];
        BuildLineChars(job.words, job.lines[job.line_begin + local], &line);

        const std::uint32_t line_id = static_cast<std::uint32_t>(job.line_begin + local);
        for (std::size_t index = 0; index < line.chars.size(); ++index) {
            const CharRecord& character = line.chars[index];
            for (std::size_t i = 0; i < pattern_length; ++i) {
                const std::vector<std::uint32_t>& keys = (*job.variants)[i];
                for (std::size_t k = 0; k < keys.size(); ++k) {
                    if (character.key == keys[k]) {
                        Candidate candidate;
                        candidate.line = &line;
                        candidate.left = character.left;
                        candidate.top = character.top;
                        candidate.width = character.width;
                        candidate.height = character.height;
                        candidate.line_id = line_id;
                        candidate.char_index = static_cast<std::uint32_t>(index);
                        scratch->slots[i].push_back(candidate);
                        break;
                    }
                }
            }
        }
    }
}

// Resident pool. Deliberately leaked: detached workers wait forever on a
// condition variable, and the process exit path owns the cleanup.
class FindThreadPool {
public:
    static FindThreadPool* Instance() {
        static FindThreadPool* instance = CreateInstance();
        return instance;
    }

    bool Usable() const {
#ifdef _WIN32
        return usable_;
#else
        return usable_ && owner_pid_ == static_cast<std::uint64_t>(OCR_GETPID());
#endif
    }

    WorkerScratch* Scratch(int index) {
        return &scratch_[index];
    }

    bool Failed() const {
        return failed_.load(std::memory_order_relaxed);
    }

    // Jobs[0..kThreadCount-1] are copied; empty jobs are allowed.
    void Run(const FindJob jobs[kThreadCount]) {
        failed_.store(false, std::memory_order_relaxed);
        {
            std::lock_guard<std::mutex> lock(mutex_);
            for (int i = 0; i < kThreadCount; ++i) {
                jobs_[i] = jobs[i];
            }
            remaining_ = kThreadCount;
            ++generation_;
        }
        cv_work_.notify_all();

        std::unique_lock<std::mutex> lock(mutex_);
        cv_done_.wait(lock, [this]() { return remaining_ == 0; });
    }

private:
    static FindThreadPool* CreateInstance() {
        try {
            return new FindThreadPool();
        } catch (...) {
            return nullptr;
        }
    }

    FindThreadPool()
        : usable_(false),
          owner_pid_(static_cast<std::uint64_t>(OCR_GETPID())),
          generation_(0),
          remaining_(0),
          failed_(false) {
        bool created = true;
        for (int i = 0; i < kThreadCount; ++i) {
            try {
                std::thread worker(&FindThreadPool::WorkerLoop, this, i);
                worker.detach();
            } catch (...) {
                created = false;
                break;
            }
        }
        usable_ = created;
    }

    void WorkerLoop(int index) {
        std::uint64_t seen = 0;
        for (;;) {
            FindJob job;
            {
                std::unique_lock<std::mutex> lock(mutex_);
                cv_work_.wait(lock, [this, seen]() { return generation_ != seen; });
                seen = generation_;
                job = jobs_[index];
            }

            try {
                RunFindJob(job, &scratch_[index]);
            } catch (...) {
                failed_.store(true, std::memory_order_relaxed);
            }

            {
                std::lock_guard<std::mutex> lock(mutex_);
                --remaining_;
                if (remaining_ == 0) {
                    cv_done_.notify_one();
                }
            }
        }
    }

    std::mutex mutex_;
    std::condition_variable cv_work_;
    std::condition_variable cv_done_;
    FindJob jobs_[kThreadCount];
    WorkerScratch scratch_[kThreadCount];
    bool usable_;
    std::uint64_t owner_pid_;
    std::uint64_t generation_;
    int remaining_;
    std::atomic<bool> failed_;
};

static std::mutex& FindMutex() {
    static std::mutex mutex;
    return mutex;
}

// Scratch for the single-thread fallback (small inputs, fork, no pool).
static WorkerScratch& FallbackScratch() {
    static thread_local WorkerScratch scratch;
    return scratch;
}

static bool AreaVerticallyAdjacent(const Candidate& previous, const Candidate& next) {
    const std::int32_t overlap =
        std::min(previous.top + previous.height, next.top + next.height) -
        std::max(previous.top, next.top);
    const std::int32_t reference = std::min(previous.height, next.height);
    return overlap * 2 >= reference;
}

static bool AreaHorizontallyAdjacent(const Candidate& previous, const Candidate& next,
                                     float gap_ratio) {
    const std::int32_t gap = next.left - (previous.left + previous.width);
    const std::int32_t reference = std::max(previous.height, next.height);
    return static_cast<float>(gap) <= gap_ratio * static_cast<float>(reference);
}

static bool AreAdjacent(const Candidate& previous, const Candidate& next, float gap_ratio) {
    return AreaVerticallyAdjacent(previous, next) &&
           AreaHorizontallyAdjacent(previous, next, gap_ratio);
}

// Candidates are sorted by (line_id, char_index). Locate the owning worker,
// binary-search the first candidate right of `after_char`, and walk forward
// until one satisfies the adjacency constraints.
static const Candidate* FindNextCandidate(
    WorkerScratch* const scratches[kThreadCount],
    const std::size_t chunk_begin[kThreadCount + 1],
    int worker_count,
    std::size_t pattern_index,
    std::uint32_t line_id,
    std::uint32_t after_char,
    const Candidate& previous,
    float gap_ratio) {
    int owner = -1;
    for (int worker = 0; worker < worker_count; ++worker) {
        if (line_id >= chunk_begin[worker] && line_id < chunk_begin[worker + 1]) {
            owner = worker;
            break;
        }
    }
    if (owner < 0) {
        return nullptr;
    }

    const std::vector<Candidate>& candidates = scratches[owner]->slots[pattern_index];
    std::size_t low = 0;
    std::size_t high = candidates.size();
    while (low < high) {
        const std::size_t middle = low + (high - low) / 2;
        const Candidate& candidate = candidates[middle];
        const bool before_or_equal =
            (candidate.line_id < line_id) ||
            (candidate.line_id == line_id && candidate.char_index <= after_char);
        if (before_or_equal) {
            low = middle + 1;
        } else {
            high = middle;
        }
    }

    for (std::size_t index = low;
         index < candidates.size() && candidates[index].line_id == line_id;
         ++index) {
        if (AreAdjacent(previous, candidates[index], gap_ratio)) {
            return &candidates[index];
        }
    }
    return nullptr;
}

static void AppendMatch(const Candidate& seed, const Candidate& last,
                        std::int32_t top, std::int32_t bottom,
                        std::vector<Match>* matches) {
    const LineChars& line = *seed.line;
    std::size_t byte_count = 0;
    for (std::uint32_t index = seed.char_index; index <= last.char_index; ++index) {
        byte_count += line.chars[index].byte_length;
    }

    Match match;
    match.text.reserve(byte_count);
    for (std::uint32_t index = seed.char_index; index <= last.char_index; ++index) {
        match.text.append(line.chars[index].bytes, line.chars[index].byte_length);
    }

    const std::int32_t left = seed.left;
    const std::int32_t right = last.left + last.width;
    match.cx = (left + right) / 2;
    match.cy = (top + bottom) / 2;
    matches->push_back(std::move(match));
}

// No Python API calls: safe to run with the GIL released.
static void RunFind(const char* data, Py_ssize_t data_length, float min_conf,
                    float gap_ratio,
                    const std::vector<std::vector<std::uint32_t>>& variants,
                    std::vector<Match>* matches) {
    matches->clear();

    WordBoxArray words;
    if (!ParseWordBoxes(data, data_length, min_conf, &words)) {
        throw std::bad_alloc();
    }

    std::vector<LineSpan> lines;
    lines.reserve(words.size);
    std::size_t start = 0;
    while (start < words.size) {
        std::size_t end = start + 1;
        while (end < words.size && SameLineBox(words.data[start], words.data[end])) {
            ++end;
        }
        std::uint64_t text_bytes = 0;
        for (std::size_t i = start; i < end; ++i) {
            text_bytes += words.data[i].text_length;
        }
        LineSpan span;
        span.row_begin = start;
        span.row_end = end;
        span.text_bytes = text_bytes;
        lines.push_back(span);
        start = end;
    }
    if (lines.empty()) {
        return;
    }

    std::uint64_t total_bytes = 0;
    for (const LineSpan& line : lines) {
        total_bytes += line.text_bytes;
    }

    FindThreadPool* pool = FindThreadPool::Instance();
    const bool use_pool = pool != nullptr && pool->Usable() &&
                          lines.size() >= 2 && total_bytes >= kParallelThresholdBytes;
    int worker_count = 1;
    if (use_pool) {
        worker_count = kThreadCount;
        if (lines.size() < static_cast<std::size_t>(worker_count)) {
            worker_count = static_cast<int>(lines.size());
        }
    }

    std::size_t chunk_begin[kThreadCount + 1] = {0};
    chunk_begin[worker_count] = lines.size();
    if (worker_count > 1) {
        std::size_t line_index = 0;
        std::uint64_t accumulated = 0;
        for (int worker = 1; worker < worker_count; ++worker) {
            const std::uint64_t desired = total_bytes *
                static_cast<std::uint64_t>(worker) /
                static_cast<std::uint64_t>(worker_count);
            while (line_index < lines.size() && accumulated < desired) {
                accumulated += lines[line_index].text_bytes;
                ++line_index;
            }
            chunk_begin[worker] = line_index;
        }
    }

    FindJob jobs[kThreadCount];
    for (int worker = 0; worker < kThreadCount; ++worker) {
        jobs[worker].words = words.data;
        jobs[worker].lines = lines.data();
        jobs[worker].variants = &variants;
        if (worker < worker_count) {
            jobs[worker].line_begin = chunk_begin[worker];
            jobs[worker].line_end = chunk_begin[worker + 1];
        } else {
            jobs[worker].line_begin = lines.size();
            jobs[worker].line_end = lines.size();
        }
    }

    WorkerScratch* scratches[kThreadCount] = {nullptr, nullptr, nullptr, nullptr};
    if (use_pool) {
        pool->Run(jobs);
        if (pool->Failed()) {
            throw std::bad_alloc();
        }
        for (int worker = 0; worker < worker_count; ++worker) {
            scratches[worker] = pool->Scratch(worker);
        }
    } else {
        WorkerScratch* scratch = &FallbackScratch();
        RunFindJob(jobs[0], scratch);
        scratches[0] = scratch;
    }

    // Chain combination: seeds come from the first target character, every
    // later step moves right on the same line under the adjacency constraints.
    const std::size_t pattern_length = variants.size();
    for (int worker = 0; worker < worker_count; ++worker) {
        const std::vector<Candidate>& seeds = scratches[worker]->slots[0];
        for (std::size_t s = 0; s < seeds.size(); ++s) {
            const Candidate& seed = seeds[s];
            const std::uint32_t line_id = seed.line_id;
            const Candidate* last = &seed;
            std::int32_t top = seed.top;
            std::int32_t bottom = seed.top + seed.height;
            bool complete = true;

            for (std::size_t i = 1; i < pattern_length; ++i) {
                const Candidate* next = FindNextCandidate(
                    scratches, chunk_begin, worker_count, i, line_id,
                    last->char_index, *last, gap_ratio);
                if (next == nullptr) {
                    complete = false;
                    break;
                }
                top = std::min(top, next->top);
                bottom = std::max(bottom, next->top + next->height);
                last = next;
            }

            if (complete) {
                AppendMatch(seed, *last, top, bottom, matches);
            }
        }
    }
}

// GIL required: converts a Python object to a single-character key.
static bool SingleCharacterKey(PyObject* object, std::uint32_t* key) {
    Py_ssize_t length = 0;
    const char* text = PyUnicode_AsUTF8AndSize(object, &length);
    if (text == nullptr) {
        return false;
    }
    if (length == 0) {
        PyErr_SetString(PyExc_ValueError, "expected a single character, got an empty string");
        return false;
    }
    const std::size_t sequence = Utf8SequenceLength(static_cast<unsigned char>(text[0]));
    if (static_cast<Py_ssize_t>(sequence) != length) {
        PyErr_SetString(PyExc_ValueError, "expected exactly one character");
        return false;
    }
    *key = EncodeKey(text, sequence);
    return true;
}

static PyObject* PyTesseractProcess(PyObject* self, PyObject* args) {
    (void)self;
    PyObject* tsv_object = nullptr;
    PyObject* pattern_object = nullptr;
    int max_edit = 0;
    float gap_ratio = 1.0f;
    float min_conf = 0.0f;
    if (!PyArg_ParseTuple(args, "OO|iff", &tsv_object, &pattern_object,
                          &max_edit, &gap_ratio, &min_conf)) {
        return nullptr;
    }
    (void)max_edit;  // retained field; confusion-set mode is exact-key recall

    if (!(gap_ratio >= 0.0f)) {
        PyErr_SetString(PyExc_ValueError, "gap_ratio must be non-negative");
        return nullptr;
    }
    if (min_conf > 1.0f) {
        min_conf /= 100.0f;  // accept either 0..1 or 0..100
    }

    Py_ssize_t pattern_length = 0;
    const char* pattern_utf8 = PyUnicode_AsUTF8AndSize(pattern_object, &pattern_length);
    if (pattern_utf8 == nullptr) {
        return nullptr;
    }

    std::vector<std::uint32_t> pattern_keys;
    if (!DecodePatternKeys(pattern_utf8, pattern_length, &pattern_keys)) {
        return nullptr;
    }
    if (pattern_keys.empty()) {
        return PyDict_New();
    }

    std::vector<std::vector<std::uint32_t>> variants(pattern_keys.size());
    {
        std::lock_guard<std::mutex> lock(ConfusionMutex());
        std::unordered_map<std::uint32_t, std::vector<std::uint32_t>>& table = ConfusionTable();
        for (std::size_t i = 0; i < pattern_keys.size(); ++i) {
            std::vector<std::uint32_t>& keys = variants[i];
            keys.push_back(pattern_keys[i]);
            std::unordered_map<std::uint32_t, std::vector<std::uint32_t>>::const_iterator found =
                table.find(pattern_keys[i]);
            if (found != table.end()) {
                for (std::size_t k = 0; k < found->second.size(); ++k) {
                    const std::uint32_t key = found->second[k];
                    if (std::find(keys.begin(), keys.end(), key) == keys.end()) {
                        keys.push_back(key);
                    }
                }
            }
        }
    }

    Py_buffer view;
    bool has_buffer = false;
    const char* data = nullptr;
    Py_ssize_t data_length = 0;
    if (PyUnicode_Check(tsv_object)) {
        data = PyUnicode_AsUTF8AndSize(tsv_object, &data_length);
        if (data == nullptr) {
            return nullptr;
        }
    } else {
        if (PyObject_GetBuffer(tsv_object, &view, PyBUF_SIMPLE) != 0) {
            return nullptr;
        }
        has_buffer = true;
        data = static_cast<const char*>(view.buf);
        data_length = view.len;
    }

    std::vector<Match> matches;
    bool failed = false;
    Py_BEGIN_ALLOW_THREADS
    try {
        std::lock_guard<std::mutex> lock(FindMutex());
        RunFind(data, data_length, min_conf, gap_ratio, variants, &matches);
    } catch (const std::bad_alloc&) {
        failed = true;
    } catch (...) {
        failed = true;
    }
    Py_END_ALLOW_THREADS

    if (has_buffer) {
        PyBuffer_Release(&view);
    }
    if (failed) {
        return PyErr_NoMemory();
    }

    PyObject* result = PyDict_New();
    if (result == nullptr) {
        return nullptr;
    }
    for (std::size_t i = 0; i < matches.size(); ++i) {
        PyObject* text = PyUnicode_DecodeUTF8(
            matches[i].text.data(),
            static_cast<Py_ssize_t>(matches[i].text.size()), "replace");
        if (text == nullptr) {
            Py_DECREF(result);
            return nullptr;
        }
        PyObject* center = Py_BuildValue("[ii]",
                                         static_cast<int>(matches[i].cx),
                                         static_cast<int>(matches[i].cy));
        if (center == nullptr) {
            Py_DECREF(text);
            Py_DECREF(result);
            return nullptr;
        }
        const int status = PyDict_SetItem(result, text, center);
        Py_DECREF(text);
        Py_DECREF(center);
        if (status < 0) {
            Py_DECREF(result);
            return nullptr;
        }
    }
    return result;
}

static PyObject* PySetConfusion(PyObject* self, PyObject* args) {
    (void)self;
    PyObject* target_object = nullptr;
    PyObject* variants_object = nullptr;
    if (!PyArg_ParseTuple(args, "OO", &target_object, &variants_object)) {
        return nullptr;
    }

    std::uint32_t target_key = 0;
    if (!SingleCharacterKey(target_object, &target_key)) {
        return nullptr;
    }

    PyObject* sequence = PySequence_Fast(
        variants_object, "variants must be an iterable of single characters");
    if (sequence == nullptr) {
        return nullptr;
    }

    std::vector<std::uint32_t> keys;
    const Py_ssize_t count = PySequence_Fast_GET_SIZE(sequence);
    keys.reserve(static_cast<std::size_t>(count));
    for (Py_ssize_t i = 0; i < count; ++i) {
        std::uint32_t key = 0;
        if (!SingleCharacterKey(PySequence_Fast_GET_ITEM(sequence, i), &key)) {
            Py_DECREF(sequence);
            return nullptr;
        }
        keys.push_back(key);
    }
    Py_DECREF(sequence);

    {
        std::lock_guard<std::mutex> lock(ConfusionMutex());
        std::vector<std::uint32_t>& stored = ConfusionTable()[target_key];
        for (std::size_t i = 0; i < keys.size(); ++i) {
            if (std::find(stored.begin(), stored.end(), keys[i]) == stored.end()) {
                stored.push_back(keys[i]);
            }
        }
    }

    Py_RETURN_NONE;
}

static PyMethodDef kModuleMethods[] = {
    {"tesseract_process", PyTesseractProcess, METH_VARARGS,
     "tesseract_process(tsv, pattern, max_edit=0, gap_ratio=1.0, min_conf=0.0) -> "
     "dict[str, list[int]]\n"
     "Locate `pattern` (up to 64 characters) in Tesseract TSV output using "
     "fixed-length UTF-8 keys and runtime confusion sets. Returns the matched "
     "OCR text mapped to its [center_x, center_y] bbox center in reading "
     "order; later occurrences of the same text overwrite earlier ones."},
    {"set_confusion", PySetConfusion, METH_VARARGS,
     "set_confusion(target, variants) -> None\n"
     "Add single-character OCR confusion variants for `target` at runtime."},
    {nullptr, nullptr, 0, nullptr}
};

static PyModuleDef kModuleDef = {
    PyModuleDef_HEAD_INIT,
    "_ocr_filter",
    nullptr,
    -1,
    kModuleMethods
};

} // namespace ocr

extern "C" PyMODINIT_FUNC PyInit__ocr_filter(void) {
    // Warm the resident worker pool before the first call.
    (void)ocr::FindThreadPool::Instance();
    return PyModule_Create(&ocr::kModuleDef);
}
