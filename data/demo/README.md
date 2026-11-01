# 演示商品与说明资料

数据集版本：demo-v1。全部为本项目自定义虚拟商品，不对应真实商家承诺。

| 商品 | ID | 价格（人民币） | 电脑仓内部高×宽×厚（cm） | 清洁方式 |
| --- | --- | --- | --- | --- |
| 轻行通勤包 | BP-A | 189.00 | 36×25×3 | 仅湿布擦拭，禁止浸泡和机洗 |
| 简装轻便包 | BP-B | 129.00 | 32×23×2 | 移除底板后冷水轻柔手洗，禁止机洗 |

## 文件职责

- products.csv：商品属性，供后续商品表导入与硬条件筛选。price_cny 单位为人民币元，尺寸字段单位为 cm；status 表示演示商品有效状态，不表示库存数量。
- documents/BP-A-v1.md、documents/BP-B-v1.md：知识库原始文档，供后续上传、解析和引用。
- document_file 为相对于本目录的文档路径；document_version 对应文档内元数据。
- Markdown 顶部元数据用于人工核对。上传时仍由服务端校验、绑定商品和版本，不能直接信任文件中的权限字段。

电脑仓尺寸只能用于初步筛选，不能单独保证电脑适配；开口尺寸、公差与余量未知。未知的最大承重等字段不填 0，不推断，也不从另一个商品补齐。

首批数据刻意保留相似商品、不同尺寸、不同清洁方式以及未提供的信息，以检查串用资料和无依据生成。这里存放事实资料，不存评测标准答案；评测题集后续放 evaluation/。

## 本机查看

在项目根目录的 PowerShell 执行：

```powershell
Import-Csv -LiteralPath .\data\demo\products.csv -Encoding UTF8 | Format-Table product_id,name,price_cny,laptop_height_cm,laptop_width_cm,laptop_depth_cm
Get-Content -LiteralPath .\data\demo\documents\BP-A-v1.md -Encoding UTF8
Get-Content -LiteralPath .\data\demo\documents\BP-B-v1.md -Encoding UTF8
```

CSV 是演示输入依据，Flyway V2 已提供对应种子快照（非通用 CSV 导入器）。Markdown 上传与分块保存接口已实现并在独立测试库验证，操作见 docs/06-Markdown入库与验收.md。向量化和检索已实现，真实智谱调用待本机密钥验证，详见 docs/07-向量入库与检索.md。上传原文当前保存到数据库 source_text，测试临时文件放 .runtime/。
