# PIM API inventory

Static controller-annotation inventory. These are source declarations, not verified live endpoints.
Ping endpoints are omitted. Request/response DTOs, authentication and runtime error contracts require separate verification.
The first frontend slice connects only category lookup/create/update; remaining operations below are planned.

| Service | Method | Path | Source |
| --- | --- | --- | --- |
| attribute | POST | `/api/attribute/attributes` | `services/attribute/src/main/kotlin/com/nexus/attribute/web/controller/AttributeController.kt:17` |
| attribute | GET | `/api/attribute/attributes/{code}` | `services/attribute/src/main/kotlin/com/nexus/attribute/web/controller/AttributeController.kt:29` |
| attribute | GET | `/api/attribute/attributes` | `services/attribute/src/main/kotlin/com/nexus/attribute/web/controller/AttributeController.kt:33` |
| attribute | POST | `/api/attribute/attribute-sets` | `services/attribute/src/main/kotlin/com/nexus/attribute/web/controller/AttributeSetController.kt:15` |
| attribute | POST | `/api/attribute/attribute-sets/{setCode}/groups` | `services/attribute/src/main/kotlin/com/nexus/attribute/web/controller/AttributeSetController.kt:20` |
| attribute | PUT | `/api/attribute/attribute-sets/{setCode}/items` | `services/attribute/src/main/kotlin/com/nexus/attribute/web/controller/AttributeSetController.kt:25` |
| attribute | GET | `/api/attribute/attribute-sets/{setCode}` | `services/attribute/src/main/kotlin/com/nexus/attribute/web/controller/AttributeSetController.kt:37` |
| catalog | POST | `/api/catalog/products` | `services/catalog/src/main/kotlin/com/nexus/catalog/web/controller/ProductController.kt:24` |
| catalog | POST | `/api/catalog/products/{productCode}/variants` | `services/catalog/src/main/kotlin/com/nexus/catalog/web/controller/ProductController.kt:34` |
| catalog | GET | `/api/catalog/products/{productCode}` | `services/catalog/src/main/kotlin/com/nexus/catalog/web/controller/ProductController.kt:45` |
| content | POST | `/api/content/products/{productCode}` | `services/content/src/main/kotlin/com/nexus/content/web/controller/ProductContentController.kt:25` |
| content | PUT | `/api/content/products/{productCode}/locales/{locale}` | `services/content/src/main/kotlin/com/nexus/content/web/controller/ProductContentController.kt:39` |
| content | PUT | `/api/content/products/{productCode}/locales/{locale}/channels/{channel}` | `services/content/src/main/kotlin/com/nexus/content/web/controller/ProductContentController.kt:53` |
| content | GET | `/api/content/products/{productCode}` | `services/content/src/main/kotlin/com/nexus/content/web/controller/ProductContentController.kt:69` |
| content | GET | `/api/content/products/{productCode}/resolve` | `services/content/src/main/kotlin/com/nexus/content/web/controller/ProductContentController.kt:73` |
| media | POST | `/api/media/assets/uploads` | `services/media/src/main/kotlin/com/nexus/media/web/controller/MediaController.kt:28` |
| media | POST | `/api/media/assets/{assetId}/complete` | `services/media/src/main/kotlin/com/nexus/media/web/controller/MediaController.kt:44` |
| media | PATCH | `/api/media/assets/{assetId}/presentation` | `services/media/src/main/kotlin/com/nexus/media/web/controller/MediaController.kt:54` |
| media | GET | `/api/media/assets/{assetId}` | `services/media/src/main/kotlin/com/nexus/media/web/controller/MediaController.kt:66` |
| media | GET | `/api/media/assets` | `services/media/src/main/kotlin/com/nexus/media/web/controller/MediaController.kt:70` |
| media | DELETE | `/api/media/assets/{assetId}` | `services/media/src/main/kotlin/com/nexus/media/web/controller/MediaController.kt:74` |
| search | POST | `/api/search/products/{productCode}/reindex` | `services/search/src/main/kotlin/com/nexus/search/web/controller/SearchController.kt:18` |
| search | GET | `/api/search/products/{productCode}` | `services/search/src/main/kotlin/com/nexus/search/web/controller/SearchController.kt:25` |
| search | GET | `/api/search/products` | `services/search/src/main/kotlin/com/nexus/search/web/controller/SearchController.kt:29` |
| taxonomy | POST | `/api/taxonomy/categories` | `services/taxonomy/src/main/kotlin/com/nexus/taxonomy/web/controller/CategoryController.kt:22` |
| taxonomy | PUT | `/api/taxonomy/categories/{categoryCode}` | `services/taxonomy/src/main/kotlin/com/nexus/taxonomy/web/controller/CategoryController.kt:27` |
| taxonomy | PUT | `/api/taxonomy/categories/{categoryCode}/attribute-sets` | `services/taxonomy/src/main/kotlin/com/nexus/taxonomy/web/controller/CategoryController.kt:44` |
| taxonomy | GET | `/api/taxonomy/categories/{categoryCode}` | `services/taxonomy/src/main/kotlin/com/nexus/taxonomy/web/controller/CategoryController.kt:51` |
| taxonomy | GET | `/api/taxonomy/categories/{categoryCode}/effective-attribute-sets` | `services/taxonomy/src/main/kotlin/com/nexus/taxonomy/web/controller/CategoryController.kt:55` |
| taxonomy | GET | `/api/taxonomy/categories/{categoryCode}/metadata` | `services/taxonomy/src/main/kotlin/com/nexus/taxonomy/web/controller/CategoryController.kt:62` |
| workflow | POST | `/api/workflow/products/{productCode}/start` | `services/workflow/src/main/kotlin/com/nexus/workflow/web/controller/WorkflowController.kt:22` |
| workflow | POST | `/api/workflow/products/{productCode}/transitions` | `services/workflow/src/main/kotlin/com/nexus/workflow/web/controller/WorkflowController.kt:27` |
| workflow | GET | `/api/workflow/products/{productCode}` | `services/workflow/src/main/kotlin/com/nexus/workflow/web/controller/WorkflowController.kt:36` |
| workflow | GET | `/api/workflow/products/{productCode}/history` | `services/workflow/src/main/kotlin/com/nexus/workflow/web/controller/WorkflowController.kt:40` |
