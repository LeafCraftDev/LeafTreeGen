# Bundled SDK & Addon APIs (`LeafTreeGen`)

`LeafTreeGen` consumes the multi-platform LeafCraft SDK (`io.github.leafcraftdev:<module>:1.0.0`) as published Maven artifacts from `LeafCraftDev/MC`, shades the runtime classes into `LeafTreeGen`, and re-exports them (`api` scope) so contributors and addon developers can use every module below without adding extra runtime dependencies.

- **IDE Javadoc & Sources:** Every published `io.github.leafcraftdev:<module>` artifact includes `-sources.jar`, `-javadoc.jar`, and embedded Markdown documentation under `META-INF/leaf-sdk/<module>/`.
- **Local Repository Guides:** Every SDK module's `README.md` and ADRs are projected into [`docs/sdk/`](README.md) in this repository for immediate browsing and code search.

## Bundled SDK Modules in This Repository

| Module | Maven Central Coordinate | Bundled & Resupplied | Documented Purpose | Guide |
|---|---|---|---|---|
| `server-api` | `io.github.leafcraftdev:server-api:1.0.0` | Yes (`api` + shaded) | Platform-neutral scheduling (`TaskScheduler`), Folia region checks, version gates, and `ServerAccessor` capability SPIs. | [`docs/sdk/server-api/README.md`](server-api/README.md) |
| `commands-api` | `io.github.leafcraftdev:commands-api:1.0.0` | Yes (`api` + shaded) | Permission-filtered command tree (`TreeCommand`), `key=value` parameters, tick-budgeted execution, and Brigadier bridge. | [`docs/sdk/commands-api/README.md`](commands-api/README.md) |
| `yaml-api` | `io.github.leafcraftdev:yaml-api:1.0.0` | Yes (`api` + shaded) | Zero-dependency, block-comment-preserving YAML parser and atomic writer (`RtpYamlConfig`). | [`docs/sdk/yaml-api/README.md`](yaml-api/README.md) |
| `predict-api` | `io.github.leafcraftdev:predict-api:1.0.0` | Yes (`api` + shaded) | Online statistical estimators (`EwmaEstimator`, `BernoulliEstimator`), `AimdLimiter`, `BudgetPacer`, `WaterFillingAllocator`, and `UnitCurve`. | [`docs/sdk/predict-api/README.md`](predict-api/README.md) |
| `anvil-api` | `io.github.leafcraftdev:anvil-api:1.0.0` | Yes (`api` + shaded) | Off-tick read-only `.mca` / `.linear` region-file decoder (`RegionFileReader`, `AnvilReader`) and selective NBT column probes. | [`docs/sdk/anvil-api/README.md`](anvil-api/README.md) |

## Full SDK Catalog

See [`docs/sdk/README.md`](README.md) for the complete 5-layer architecture overview across all 16 SDK modules.
