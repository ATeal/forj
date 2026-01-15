---
name: check-versions
description: Check and update forj template dependency versions. Use when maintaining forj or when user asks about template versions.
---

# Template Version Maintenance

Check forj template dependencies against latest available versions.

## Commands

| Command | Action |
|---------|--------|
| `/check-versions` | Run version check, show outdated packages |
| `/check-versions update` | Suggest updates for outdated packages |

## Instructions

### Step 1: Run the Version Checker

```bash
bb check-versions
```

This checks:
- **npm packages** (react, expo, shadow-cljs, etc.) against npm registry
- **Clojure deps** (reitit, next.jdbc, etc.) against Clojars/Maven Central
- **Conditional versions** (e.g., shadow-cljs 2.x vs 3.x based on Java version)

### Step 2: Analyze Results

The output shows:
- `✓` - Version is current
- `⬆️  UPDATE` - Newer version available
- `⚠️  (fetch failed)` - Could not check (usually transient network issue)
- `(conditional)` - Version depends on environment (e.g., Java version)

**IMPORTANT**: Not all updates should be applied:
- **Expo SDK** - Versions are tied together. Only update when doing a full SDK upgrade.
- **react-native** - Must match Expo SDK's expected version.
- **react/react-dom** - Must match react-native peer dependency.
- **shadow-cljs 3.x** - Requires Java 21+. Only suggest if user has Java 21+.

### Step 3: Update versions.edn

The single source of truth is:
```
packages/forj-skill/clj-init/versions.edn
```

**Version Entry Format:**

Simple version:
```clojure
:react {:version "19.2.3"
        :notes "Must match react-native peer dep"}
```

Conditional version (based on environment):
```clojure
:shadow-cljs {:versions [{:version "^3.3.5"
                          :when {:java ">= 21"}
                          :notes "ESM support, smaller bundles"}
                         {:version "^2.28.23"
                          :when {:java "< 21"}
                          :notes "Works with Java 11+"}]
              :default "^2.28.23"}
```

### Step 4: Update Templates

After editing versions.edn, update the actual template files that use these versions:

**npm packages** - Update package.json files:
- `packages/forj-skill/clj-init/templates/mobile/package.json`
- `packages/forj-skill/clj-init/templates/fullstack-mobile/package.json`
- `packages/forj-skill/clj-init/templates/web/package.json`
- `packages/forj-skill/clj-init/templates/fullstack/package.json`

**Clojure deps** - Update deps.edn files:
- `packages/forj-skill/clj-init/templates/api/deps.edn`
- `packages/forj-skill/clj-init/templates/fullstack/deps.edn`
- `packages/forj-skill/clj-init/templates/fullstack-mobile/deps.edn`
- `packages/forj-skill/clj-init/templates/lib/deps.edn`

### Step 5: Validate and Install

After updating:

```bash
bb test           # Ensure tests pass
bb install        # Reinstall skills with updated templates
```

## Expo SDK Upgrades

Expo SDK upgrades are major changes. When upgrading:

1. Check Expo changelog for breaking changes
2. Update these together:
   - `expo` version
   - `react-native` (to version Expo expects)
   - `react` / `react-dom` (to match react-native peer dep)
   - All `expo-*` packages (status-bar, asset, etc.)

3. Test by scaffolding a new mobile project:
   ```bash
   cd /tmp
   claude /clj-init test-app  # Select mobile
   cd test-app
   npm install
   npx expo start
   ```

## Conditional Version Rules

The versions.edn supports conditional versions with `:when` clauses:

```clojure
:when {:java ">= 21"}   ; Requires Java 21 or higher
:when {:java "< 21"}    ; For Java below 21
```

When evaluating:
1. Check current Java version with `java -version`
2. Select version matching the condition
3. Fall back to `:default` if no condition matches

## Notes

- SNAPSHOT versions are automatically ignored (development builds)
- The checker shows conditional versions with `(conditional)` marker
- Update the "Last updated" comment at bottom of versions.edn after changes
