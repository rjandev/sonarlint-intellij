/*
 * SonarLint for IntelliJ IDEA
 * Copyright (C) 2015-2022 SonarSource
 * sonarlint@sonarsource.com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */
package org.sonarlint.intellij.core

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.serviceContainer.NonInjectable
import org.apache.commons.io.FileUtils
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.sonarlint.intellij.SonarLintIntelliJClient
import org.sonarlint.intellij.common.util.SonarLintUtils.getService
import org.sonarlint.intellij.config.Settings.getGlobalSettings
import org.sonarlint.intellij.config.Settings.getSettingsFor
import org.sonarlint.intellij.config.global.ServerConnection
import org.sonarlint.intellij.util.GlobalLogOutput
import org.sonarsource.sonarlint.core.SonarLintBackendImpl
import org.sonarsource.sonarlint.core.clientapi.InitializeParams
import org.sonarsource.sonarlint.core.clientapi.SonarLintBackend
import org.sonarsource.sonarlint.core.clientapi.config.binding.BindingConfigurationDto
import org.sonarsource.sonarlint.core.clientapi.config.binding.DidUpdateBindingParams
import org.sonarsource.sonarlint.core.clientapi.config.scope.ConfigurationScopeDto
import org.sonarsource.sonarlint.core.clientapi.config.scope.DidAddConfigurationScopesParams
import org.sonarsource.sonarlint.core.clientapi.config.scope.DidRemoveConfigurationScopeParams
import org.sonarsource.sonarlint.core.clientapi.connection.config.DidAddConnectionParams
import org.sonarsource.sonarlint.core.clientapi.connection.config.DidRemoveConnectionParams
import org.sonarsource.sonarlint.core.clientapi.connection.config.DidUpdateConnectionParams
import org.sonarsource.sonarlint.core.clientapi.connection.config.SonarCloudConnectionConfigurationDto
import org.sonarsource.sonarlint.core.clientapi.connection.config.SonarQubeConnectionConfigurationDto
import org.sonarsource.sonarlint.core.clientapi.rules.GetActiveRuleDetailsResponse
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture

class BackendService @NonInjectable constructor(private val backend: SonarLintBackend) : Disposable {
    constructor() : this(SonarLintBackendImpl(SonarLintIntelliJClient()))

    private var initialized = false

    fun startOnce() {
        if (initialized) return
        initialized = true
        initialize()
    }

    private fun initialize() {
        migrateStoragePath()
        val nodeJsManager = getService(NodeJsManager::class.java)
        val serverConnections = getGlobalSettings().serverConnections
        val sonarCloudConnections =
            serverConnections.filter { it.isSonarCloud }.map { toSonarCloudBackendConnection(it) }
        val sonarQubeConnections =
            serverConnections.filter { !it.isSonarCloud }.map { toSonarQubeBackendConnection(it) }
        backend.initialize(
            InitializeParams(
                getLocalStoragePath(),
                EmbeddedPlugins.findEmbeddedPlugins(),
                EmbeddedPlugins.getExtraPluginsForConnectedMode(),
                EmbeddedPlugins.getEmbeddedPluginsForConnectedMode(),
                EmbeddedPlugins.enabledLanguagesInStandaloneMode,
                EmbeddedPlugins.enabledLanguagesInConnectedMode,
                nodeJsManager.nodeJsVersion,
                false,
                sonarQubeConnections,
                sonarCloudConnections
            )
        )
        ApplicationManager.getApplication().messageBus.connect()
            .subscribe(ProjectManager.TOPIC, object : ProjectManagerListener {
                override fun projectOpened(project: Project) {
                    this@BackendService.projectOpened(project)
                }

                override fun projectClosing(project: Project) {
                    this@BackendService.projectClosed(project)
                }
            })
    }

    /**
     * SLI-657
     */
    private fun migrateStoragePath() {
        val oldPath = Paths.get(PathManager.getConfigPath()).resolve("sonarlint/storage")
        val newPath = getLocalStoragePath()
        if (Files.exists(oldPath) && !Files.exists(newPath)) {
            try {
                FileUtils.moveDirectory(oldPath.toFile(), newPath.toFile())
            } catch (e: IOException) {
                getService(GlobalLogOutput::class.java).logError("Unable to migrate storage", e)
            } finally {
                FileUtils.deleteQuietly(oldPath.toFile())
            }
        }
    }

    private fun getLocalStoragePath(): Path = Paths.get(PathManager.getSystemPath()).resolve("sonarlint/storage")

    fun connectionAdded(createdConnection: ServerConnection) {
        backend.connectionService.didAddConnection(DidAddConnectionParams(toBackendConnection(createdConnection)))
    }

    fun connectionUpdated(updatedConnection: ServerConnection) {
        backend.connectionService.didUpdateConnection(DidUpdateConnectionParams(toBackendConnection(updatedConnection)))
    }

    fun connectionRemoved(connectionId: String) {
        backend.connectionService.didRemoveConnection(DidRemoveConnectionParams(connectionId))
    }

    private fun toBackendConnection(createdConnection: ServerConnection): Either<SonarQubeConnectionConfigurationDto, SonarCloudConnectionConfigurationDto> {
        val connectionConfig: Either<SonarQubeConnectionConfigurationDto, SonarCloudConnectionConfigurationDto> =
            if (createdConnection.isSonarCloud) {
                Either.forRight(
                    toSonarCloudBackendConnection(createdConnection)
                )
            } else {
                Either.forLeft(
                    toSonarQubeBackendConnection(createdConnection)
                )
            }
        return connectionConfig
    }

    private fun toSonarQubeBackendConnection(createdConnection: ServerConnection) = SonarQubeConnectionConfigurationDto(
        createdConnection.name, createdConnection.hostUrl
    )

    private fun toSonarCloudBackendConnection(createdConnection: ServerConnection) =
        SonarCloudConnectionConfigurationDto(
            createdConnection.name, createdConnection.organizationKey!!
        )

    internal fun projectOpened(project: Project) {
        val binding = getService(project, ProjectBindingManager::class.java).binding
        backend.configurationService.didAddConfigurationScopes(
            DidAddConfigurationScopesParams(
                listOf(
                    toBackendConfigurationScope(project, binding)
                )
            )
        )
    }

    internal fun projectClosed(project: Project) {
        ModuleManager.getInstance(project).modules.forEach { moduleRemoved(it) }
        backend.configurationService.didRemoveConfigurationScope(
            DidRemoveConfigurationScopeParams(
                projectId(
                    project
                )
            )
        )
    }

    private fun toBackendConfigurationScope(project: Project, binding: ProjectBinding?) = ConfigurationScopeDto(
        projectId(project), null, true, project.name, BindingConfigurationDto(
            binding?.connectionName, binding?.projectKey, areBindingSuggestionsDisabledFor(project)
        )
    )


    fun projectBound(project: Project, newBinding: ProjectBinding) {
        backend.configurationService.didUpdateBinding(
            DidUpdateBindingParams(
                projectId(project), BindingConfigurationDto(
                    newBinding.connectionName, newBinding.projectKey, areBindingSuggestionsDisabledFor(project)
                )
            )
        )
        newBinding.moduleBindingsOverrides.forEach { (module, projectKey) ->
            backend.configurationService.didUpdateBinding(
                DidUpdateBindingParams(
                    moduleId(module), BindingConfigurationDto(
                        // we don't want binding suggestions for modules
                        newBinding.connectionName, projectKey, true
                    )
                )
            )
        }
    }

    fun projectUnbound(project: Project) {
        backend.configurationService.didUpdateBinding(
            DidUpdateBindingParams(
                projectId(project), BindingConfigurationDto(null, null, areBindingSuggestionsDisabledFor(project))
            )
        )

    }

    fun moduleAdded(module: Module) {
        val moduleProjectKey = getService(module, ModuleBindingManager::class.java).configuredProjectKey
        val projectBinding = getService(module.project, ProjectBindingManager::class.java).binding
        backend.configurationService.didAddConfigurationScopes(
            DidAddConfigurationScopesParams(
                listOf(
                    ConfigurationScopeDto(
                        moduleId(module), projectId(module.project), true, module.name, BindingConfigurationDto(
                            projectBinding?.connectionName, projectBinding?.let { moduleProjectKey }, true
                        )
                    )
                )
            )
        )
    }

    fun moduleRemoved(module: Module) {
        backend.configurationService.didRemoveConfigurationScope(
            DidRemoveConfigurationScopeParams(
                moduleId(
                    module
                )
            )
        )
        uniqueIdentifierForModules.remove(module)
    }

    fun moduleUnbound(module: Module) {
        backend.configurationService.didUpdateBinding(
            DidUpdateBindingParams(
                moduleId(module), BindingConfigurationDto(
                    // we don't want binding suggestions for modules
                    null, null, true
                )
            )
        )
    }

    private fun areBindingSuggestionsDisabledFor(project: Project) =
        !getSettingsFor(project).isBindingSuggestionsEnabled

    fun bindingSuggestionsDisabled(project: Project) {
        val binding = getService(project, ProjectBindingManager::class.java).binding
        backend.configurationService.didUpdateBinding(
            DidUpdateBindingParams(
                projectId(project), BindingConfigurationDto(binding?.connectionName, binding?.projectKey, true)
            )
        )
    }

    fun getActiveRuleDetails(module: Module, ruleKey: String): CompletableFuture<GetActiveRuleDetailsResponse> {
        return backend.activeRulesService.getActiveRuleDetails(moduleId(module), ruleKey)
    }

    companion object {
        private var moduleCount = 1
        internal val uniqueIdentifierForModules = hashMapOf<Module, String>()
        fun projectId(project: Project) = project.projectFilePath ?: "DEFAULT_PROJECT"

        fun moduleId(module: Module): String {
            // there is no reliable unique identifier for modules, but a module is represented by a single object
            return uniqueIdentifierForModules.computeIfAbsent(module) { m -> m.name + "-" + moduleCount++ }
        }
    }

    override fun dispose() {
        backend.shutdown()
    }
}
