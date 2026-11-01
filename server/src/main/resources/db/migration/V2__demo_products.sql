-- demo-v1，来自 data/demo/products.csv 的固定演示种子快照。
INSERT INTO products (product_id, name, category, price_cny, laptop_height_cm, laptop_width_cm, laptop_depth_cm, status)
VALUES ('BP-A', '轻行通勤包', '背包', 189.00, 36, 25, 3, 'ACTIVE'),
       ('BP-B', '简装轻便包', '背包', 129.00, 32, 23, 2, 'ACTIVE');
