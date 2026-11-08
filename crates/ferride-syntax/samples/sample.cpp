#include <iostream>
#include <map>
#include <string>
#include <vector>

namespace stats {

// Counts how often each word appears.
template <typename T>
class Counter {
public:
    void add(const T& item) { ++counts_[item]; }

    std::vector<std::pair<T, int>> top(std::size_t n) const {
        std::vector<std::pair<T, int>> out(counts_.begin(), counts_.end());
        std::sort(out.begin(), out.end(),
                  [](const auto& a, const auto& b) { return a.second > b.second; });
        if (out.size() > n) out.resize(n);
        return out;
    }

private:
    std::map<T, int> counts_;
};

}  // namespace stats

int main() {
    stats::Counter<std::string> words;
    for (std::string w; std::cin >> w;) words.add(w);
    for (const auto& [word, count] : words.top(3)) {
        std::cout << word << ": " << count << '\n';
    }
    return 0;
}
