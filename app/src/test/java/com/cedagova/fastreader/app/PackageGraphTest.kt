package com.cedagova.fastreader.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `:app`'s package graph has no cycles (A197-F005, `docs/app-shell.md`).
 *
 * The graph is read from the tracked sources rather than from compiled classes:
 * a node is a Kotlin package under `app/src/main/java`, and an edge is an
 * `import` of another of those packages. Every package is its own node — a
 * feature's `ui` package is a separate layer from the feature itself, and so is
 * `library.store` from `library` — which is the strictest reading of "package
 * graph" and the one the convention document states.
 *
 * Two imports are not edges: the generated `R` and `BuildConfig`, which live in
 * the root package only because that is the namespace. They carry resources and
 * build values, not code of the root package, and counting them would make
 * every screen depend on the composition root.
 *
 * Plain JVM, no Robolectric: it runs in `testDebugUnitTest`, which is CI's "Unit
 * tests" step and part of `./gradlew check`.
 */
class PackageGraphTest {

    @Test
    fun `the app package graph has no cycles`() {
        val graph = appPackageGraph()
        val cycles = stronglyConnected(graph.edges).filter { it.size > 1 }
        val report = cycles.joinToString("\n\n") { cycle ->
            "cycle between ${cycle.sorted().joinToString(", ") { it.short() }}:\n" +
                graph.evidence
                    .filter { (edge, _) -> edge.first in cycle && edge.second in cycle }
                    .toSortedMap(compareBy({ it.first }, { it.second }))
                    .entries.joinToString("\n") { (edge, why) ->
                        "  ${edge.first.short()} -> ${edge.second.short()}: ${why.sorted().joinToString()}"
                    }
        }
        assertTrue("the :app package graph must be acyclic (docs/app-shell.md):\n$report", cycles.isEmpty())
    }

    @Test
    fun `the scan sees the whole app`() {
        // Silence is not success: a scan that found nothing would pass the check
        // above vacuously, so the shape it expects is pinned loosely here.
        val graph = appPackageGraph()
        assertTrue("found only ${graph.packages.size} packages", graph.packages.size >= 10)
        assertTrue(ROOT in graph.packages)
        assertTrue(
            "found only ${graph.edges.values.sumOf { it.size }} edges",
            graph.edges.values.sumOf { it.size } >= 20,
        )
    }

    @Test
    fun `a cycle is found and an edge into the generated classes is not one`() {
        val edges = mapOf(
            "a" to setOf("b"),
            "b" to setOf("c"),
            "c" to setOf("a"),
            "d" to setOf("a"),
        )
        assertEquals(listOf(setOf("a", "b", "c")), stronglyConnected(edges).filter { it.size > 1 })

        val known = setOf(ROOT, "$ROOT.library", "$ROOT.library.ui")
        assertEquals(null, importedPackage("$ROOT.R", known))
        assertEquals(null, importedPackage("$ROOT.BuildConfig", known))
        assertEquals("$ROOT.library", importedPackage("$ROOT.library.LaunchDestination.Reader", known))
        assertEquals("$ROOT.library", importedPackage("$ROOT.library.launchDestination", known))
        assertEquals("$ROOT.library.ui", importedPackage("$ROOT.library.ui.LibraryRoute as Route", known))
        assertEquals(null, importedPackage("com.cedagova.reader.account.ReaderAccountGraph", known))
    }

    private class PackageGraph(
        val packages: Set<String>,
        val edges: Map<String, Set<String>>,
        /** Which files, importing what, make each edge — the failure message. */
        val evidence: Map<Pair<String, String>, Set<String>>,
    )

    private fun appPackageGraph(): PackageGraph {
        val sources = File(repositoryRoot(), "app/src/main/java")
        check(sources.isDirectory) { "$sources is missing" }
        val files = sources.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val declared = files.associateWith { file ->
            file.useLines { lines ->
                lines.firstOrNull { it.startsWith("package ") }?.removePrefix("package ")?.trim()
            } ?: error("${file.relativeTo(sources)} declares no package")
        }
        val packages = declared.values.toSet()
        val edges = mutableMapOf<String, MutableSet<String>>()
        val evidence = mutableMapOf<Pair<String, String>, MutableSet<String>>()
        for ((file, from) in declared) {
            file.useLines { lines ->
                lines.filter { it.startsWith("import ") }.forEach { line ->
                    val imported = line.removePrefix("import ").trim()
                    val to = importedPackage(imported, packages) ?: return@forEach
                    if (to == from) return@forEach
                    edges.getOrPut(from) { mutableSetOf() }.add(to)
                    evidence.getOrPut(from to to) {
                        mutableSetOf()
                    }.add("${file.name} (${imported.substringAfterLast('.')})")
                }
            }
        }
        return PackageGraph(packages, edges, evidence)
    }

    private fun String.short(): String = removePrefix(ROOT).removePrefix(".").ifEmpty { "(root)" }

    private companion object {
        const val ROOT = "com.cedagova.fastreader"

        /** Generated into the root package by the build, not written there. */
        val GENERATED = setOf("R", "BuildConfig")

        /**
         * The app package an import names, or null when it is outside the app or
         * one of the generated classes. The longest declared package that
         * prefixes the import wins, so nested classes and top-level functions
         * resolve to the package that declares them.
         */
        fun importedPackage(statement: String, packages: Set<String>): String? {
            val path = statement.substringBefore(" as ").trim().removeSuffix(".*")
            if (path != ROOT && !path.startsWith("$ROOT.")) return null
            val owner =
                packages.filter { path == it || path.startsWith("$it.") }.maxByOrNull { it.length } ?: return null
            val firstName = path.removePrefix(owner).removePrefix(".").substringBefore('.')
            return if (owner == ROOT && firstName in GENERATED) null else owner
        }

        /** Tarjan's strongly connected components. */
        fun stronglyConnected(edges: Map<String, Set<String>>): List<Set<String>> {
            val nodes = (edges.keys + edges.values.flatten()).sorted()
            val index = mutableMapOf<String, Int>()
            val low = mutableMapOf<String, Int>()
            val stack = ArrayDeque<String>()
            val onStack = mutableSetOf<String>()
            val components = mutableListOf<Set<String>>()
            var next = 0

            fun visit(node: String) {
                index[node] = next
                low[node] = next
                next++
                stack.addLast(node)
                onStack += node
                for (target in edges[node].orEmpty().sorted()) {
                    if (target !in index) {
                        visit(target)
                        low[node] = minOf(low.getValue(node), low.getValue(target))
                    } else if (target in onStack) {
                        low[node] = minOf(low.getValue(node), index.getValue(target))
                    }
                }
                if (low[node] == index[node]) {
                    val component = mutableSetOf<String>()
                    do {
                        val member = stack.removeLast()
                        onStack -= member
                        component += member
                    } while (member != node)
                    components += component
                }
            }

            nodes.forEach { if (it !in index) visit(it) }
            return components
        }
    }
}
