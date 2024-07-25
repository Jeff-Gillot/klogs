package be.delta.flow.klogs

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.sangupta.murmur.Murmur2
import io.fabric8.kubernetes.api.model.Pod
import io.fabric8.kubernetes.client.*
import java.awt.Color
import java.util.concurrent.ConcurrentHashMap

fun main(args: Array<String>) {
    Logger().main(args)
}

class Logger : CliktCommand() {
    private val podName: String? by argument().optional()
    private val labels: List<String> by option("-l", "--labels").multiple()
    private val allNamespaces: Boolean by option("-an", "-allNamespaces").flag()
    private val allContexts: Boolean by option("-ac", "-allContexts").flag()

    override fun run() {
        when {
            podName == null && labels.isEmpty() -> println("Logging every pods ${if (allNamespaces) "in all namespaces " else ""}${if (allContexts) "in all contexts" else ""}")
            labels.isEmpty() -> println("Logging pods with name containing '$podName' ${if (allNamespaces) "in all namespaces " else ""}${if (allContexts) "in all contexts" else ""}")
            podName == null && labels.isNotEmpty() -> println("Logging pods with labels $labels ${if (allNamespaces) "in all namespaces " else ""}${if (allContexts) "in all contexts" else ""}")
            else -> println("Logging pods with name containing '$podName' and labels $labels ${if (allNamespaces) "in all namespaces " else ""}${if (allContexts) "in all contexts" else ""}")
        }

        val contexts = KubernetesClientBuilder().build().use { client ->
            if (allContexts) client.configuration.contexts.map { it.name } else listOf(client.configuration.currentContext.name)
        }

        contexts.forEach { context ->
            val client = KubernetesClientBuilder().withConfig(Config.autoConfigure(context)).build()
            val namespace = client.configuration.currentContext.context.namespace ?: "default"
            val prefixes = when {
                allNamespaces && allContexts -> listOf(context, namespace)
                allNamespaces -> listOf(namespace)
                allContexts -> listOf(context)
                else -> emptyList()
            }
            client
                .pods()
                .let { if (allNamespaces) it else it.inNamespace(namespace) }
                .watch(PodWatcher(client, podName, labels, prefixes))
        }
        while (true) {
            Thread.sleep(1000)
        }
    }
}

class PodWatcher(
    private val client: KubernetesClient,
    private val podName: String?,
    private val labels: List<String>,
    private val prefixes: List<String>,
) : Watcher<Pod> {
    override fun eventReceived(action: Watcher.Action, resource: Pod) {
        if (podName != null && !resource.metadata.name.contains(podName, ignoreCase = true)) return
        if (!resource.hasLabel(labels)) return
        if (resource.status.containerStatuses.mapNotNull { it.state.running }.isEmpty()) return
        PodLogger(client, resource, prefixes).startLogging()
    }

    override fun onClose(cause: WatcherException) {}
}

fun Pod.hasLabel(labels: List<String>): Boolean {
    if (labels.isEmpty()) return true
    return labels.all { expected ->
        metadata.labels.any { (key, value) -> key == expected || value == expected }
    }
}

class PodLogger(
    private val client: KubernetesClient,
    private val pod: Pod,
    prefixes: List<String>,
) {
    private val podName = pod.metadata.name
    private val hash = Murmur2.hash(pod.metadata.uid.toByteArray(), pod.metadata.uid.toByteArray().size, 1337)
    private val podColor = Color(
        192 + (hash % 64).toInt(),
        192 + ((hash / 64) % 64).toInt(),
        192 + ((hash / 4096) % 64).toInt(),
    )

    private val prefix = buildString {
        prefixes.forEach {
            append(podColor.colored(it))
            append(" | ")
        }
        append(podColor.colored(podName))
    }

    fun startLogging() {
        runningLoggers.computeIfAbsent(pod.metadata.uid) {
            val thread = Thread {
                try {

                    println("+ $prefix")
                    client
                        .pods()
                        .resource(pod)
                        .watchLog()
                        .output
                        .reader()
                        .forEachLine {
                            println("$prefix | $it")
                        }
                } finally {
                    println("- $prefix")
                    runningLoggers.remove(podName)
                }
            }
            thread.start()
            thread
        }
    }

    companion object {
        private val runningLoggers = ConcurrentHashMap<String, Thread>()
    }

}

fun Color.colored(text: String): String =
    "\u001b[38;2;${red};${green};${blue}m$text\u001b[0m"

fun Color.colored(textProvider: () -> String): String = colored(textProvider.invoke())
