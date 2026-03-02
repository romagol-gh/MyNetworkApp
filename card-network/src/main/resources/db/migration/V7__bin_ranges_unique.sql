-- Prevent duplicate BIN ranges (same low/high pair cannot be registered twice)
ALTER TABLE bin_ranges ADD CONSTRAINT uq_bin_ranges_low_high UNIQUE (low, high);
