// Test-only DuckDB 2.0 C++ extension. No I/O, parser hooks, SQL callbacks or autoload.
#include "duckdb.hpp"
#include "duckdb/function/window_function.hpp"
#include "duckdb/common/vector/vector_writer.hpp"
#include "duckdb/main/extension/extension_loader.hpp"

namespace duckdb {
static void Bounds(WindowBoundsSet &required, const BoundWindowExpression &) {
	required.insert(PARTITION_BEGIN);
}
static void Evaluate(ExecutionContext &, DataChunk &input, DataChunk &bounds, Vector &result,
                     idx_t offset, OperatorSinkInput &) {
	result.SetVectorType(VectorType::FLAT_VECTOR);
	auto starts = FlatVector::GetData<idx_t>(bounds.data[PARTITION_BEGIN]);
	auto values = FlatVector::Writer<int64_t>(result, input.size());
	for (idx_t i = 0; i < input.size(); i++) {
		values.WriteValue(static_cast<int64_t>(offset + i - starts[i] + 1));
	}
}
static WindowFunction Window(const char *name) {
	return WindowFunction(name, {}, LogicalType::BIGINT, ExpressionType::WINDOW_FUNCTION,
	                      nullptr, Bounds, nullptr, nullptr, nullptr, nullptr, nullptr, Evaluate);
}
static void Scalar(DataChunk &, ExpressionState &, Vector &result) {
	result.SetVectorType(VectorType::CONSTANT_VECTOR);
	ConstantVector::GetData<int64_t>(result)[0] = -7;
}
} // namespace duckdb

extern "C" {
DUCKDB_CPP_EXTENSION_ENTRY(brikk_window, loader) {
	loader.SetDescription("Brikk SQL test-only real window registration");
	loader.RegisterFunction(duckdb::Window("brikk_row_number"));
	loader.RegisterFunction(duckdb::Window("brikk_kind"));
	// Scalar/aggregate/window entries share one schema namespace in DuckDB. Use a
	// second schema to test a genuine catalog name collision without replacing a kind.
	loader.CreateSchema("brikk_scalar");
	loader.UseDefaultSchema("brikk_scalar");
	loader.RegisterFunction(duckdb::ScalarFunction("brikk_kind", {}, duckdb::LogicalType::BIGINT, duckdb::Scalar));
}
}
