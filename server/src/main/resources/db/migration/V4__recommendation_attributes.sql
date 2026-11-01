-- Demo facts verified against both version-1 Markdown cleaning instructions.
-- NULL means unknown for future products; it must not be treated as washable.
ALTER TABLE products ADD COLUMN machine_washable boolean;
UPDATE products SET machine_washable = false WHERE product_id IN ('BP-A', 'BP-B');
