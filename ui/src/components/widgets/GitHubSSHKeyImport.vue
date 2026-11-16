// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

<template>
  <div class="github-import">
    <a-form-item :label="$t('label.ssh.github.username')">
      <a-input-search
        v-model:value="githubUsername"
        :disabled="loading"
        :loading="githubLoading"
        :enter-button="$t('label.ssh.github.fetch')"
        @search="fetchGitHubKeys"
        @pressEnter="event => event.preventDefault()">
        <template #prefix><github-outlined /></template>
      </a-input-search>
    </a-form-item>
    <a-alert v-if="githubError" :message="githubError" type="error" show-icon />
    <p v-if="githubFetched && !githubKeys.length">{{ $t('message.ssh.github.empty') }}</p>
    <a-table
      v-if="githubKeys.length"
      size="small"
      :columns="columns"
      :data-source="githubKeys"
      :row-selection="rowSelection"
      :row-key="key => key.id"
      :pagination="false"
      :scroll="{ x: 560, y: 260 }">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'fingerprint'">
          <a-tooltip :title="record.fingerprint"><code class="fingerprint">{{ record.fingerprint }}</code></a-tooltip>
        </template>
        <template v-else-if="column.key === 'name'">
          <a-input v-model:value="record.name" size="small" :aria-label="$t('label.ssh.github.hostname')" :disabled="loading || record.imported" />
          <a-typography-text v-if="record.imported" type="success" class="import-status"><check-circle-outlined /> {{ $t('message.ssh.github.imported') }}</a-typography-text>
          <a-typography-text v-if="record.error" type="danger" class="import-status">{{ record.error }}</a-typography-text>
        </template>
      </template>
      <template #expandedRowRender="{ record }"><code class="public-key">{{ record.publickey }}</code></template>
    </a-table>
  </div>
</template>

<script>
import { postAPI } from '@/api'
import { validGitHubUsername, fetchGitHubSSHKeys } from '@/utils/githubSSHKeys'

export default {
  name: 'GitHubSSHKeyImport',
  props: {
    scope: { type: Object, default: () => ({}) }
  },
  emits: ['loading', 'imported'],
  data () {
    return {
      loading: false,
      githubUsername: '',
      githubKeys: [],
      githubLoading: false,
      githubFetched: false,
      githubError: '',
      githubRequest: 0,
      githubController: null
    }
  },
  computed: {
    canImport () {
      return 'registerSSHKeyPair' in this.$store.getters.apis
    },
    columns () {
      return [
        { title: this.$t('label.type'), dataIndex: 'type', key: 'type', width: 110 },
        { title: this.$t('label.fingerprint'), key: 'fingerprint', width: 215 },
        { title: this.$t('label.ssh.github.hostname'), key: 'name', width: 210 }
      ]
    },
    rowSelection () {
      return {
        selectedRowKeys: this.githubKeys.filter(key => key.selected).map(key => key.id),
        onChange: ids => {
          this.githubKeys.forEach(key => { key.selected = !key.imported && ids.includes(key.id) })
        },
        getCheckboxProps: key => ({ disabled: this.loading || key.imported })
      }
    }
  },
  watch: {
    githubUsername () {
      this.cancelGitHubFetch()
      this.githubKeys = []
      this.githubFetched = false
      this.githubError = ''
    }
  },
  beforeUnmount () {
    this.cancelGitHubFetch()
  },
  methods: {
    cancelGitHubFetch () {
      this.githubRequest++
      if (this.githubController) this.githubController.abort()
      this.githubLoading = false
    },
    async fetchGitHubKeys () {
      if (this.loading || !this.canImport) return
      this.cancelGitHubFetch()
      this.githubError = ''
      this.githubKeys = []
      this.githubFetched = false
      const username = this.githubUsername.trim()
      if (!validGitHubUsername(username)) {
        this.githubError = this.$t('message.ssh.github.username')
        return
      }
      const request = this.githubRequest
      const controller = new AbortController()
      this.githubController = controller
      const timeout = setTimeout(() => controller.abort(), 30000)
      this.githubLoading = true
      try {
        const keys = await fetchGitHubSSHKeys(username, controller.signal)
        if (request !== this.githubRequest) return
        this.githubKeys = keys
        this.githubFetched = true
      } catch (error) {
        if (request === this.githubRequest) this.githubError = this.$t('message.ssh.github.' + (error.code || 'fetch'))
      } finally {
        clearTimeout(timeout)
        if (request === this.githubRequest) this.githubLoading = false
      }
    },
    async importGitHubKeys () {
      if (this.loading || !this.canImport || this.githubLoading) return
      const selected = this.githubKeys.filter(key => key.selected && !key.imported)
      const names = selected.map(key => key.name.trim())
      if (!selected.length || names.some(name => !name) || new Set(names).size !== names.length) {
        this.githubError = this.$t('message.ssh.github.selection')
        return
      }
      this.githubError = ''
      this.loading = true
      this.$emit('loading', true)
      const scope = { ...this.scope }
      const imported = []
      try {
        for (const key of selected) {
          key.error = ''
          try {
            await postAPI('registerSSHKeyPair', { ...scope, name: key.name.trim(), publickey: key.publickey })
            key.imported = true
            key.selected = false
            imported.push(key.name.trim())
          } catch (error) {
            key.error = error?.response?.data?.registersshkeypairresponse?.errortext || error.message || this.$t('message.ssh.github.importerror')
          }
        }
      } finally {
        this.loading = false
        this.$emit('loading', false)
        if (imported.length) this.$emit('imported', imported)
      }
    }
  }
}
</script>

<style scoped lang="less">
.github-import {
  .ant-alert { margin-bottom: 12px; }
  .fingerprint {
    display: block;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-size: 12px;
  }
  .public-key { overflow-wrap: anywhere; }
  .import-status { display: block; margin-top: 4px; font-size: 12px; }
}
</style>
