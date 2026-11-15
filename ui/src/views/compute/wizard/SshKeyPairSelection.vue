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
  <div>
    <div class="ssh-toolbar">
      <a-button v-if="'registerSSHKeyPair' in $store.getters.apis" type="primary" @click="showAddKey = true">
        {{ $t('label.add.ssh.key') }}
      </a-button>
      <a-input-search
        :placeholder="$t('label.search')"
        v-model:value="filter"
        @search="handleSearch" />
    </div>
    <a-modal
      :visible="showAddKey"
      :title="$t('label.add.ssh.key')"
      :confirmLoading="savingKey"
      :closable="!savingKey"
      :maskClosable="false"
      :keyboard="!savingKey"
      @ok="addKey"
      @cancel="showAddKey = false">
      <a-form layout="vertical">
        <a-form-item :label="$t('label.name')" required>
          <a-input v-model:value="keyName" :disabled="savingKey" />
        </a-form-item>
        <a-form-item :label="$t('label.publickey')" required>
          <a-textarea v-model:value="publicKey" :rows="4" :disabled="savingKey" placeholder="ssh-ed25519 AAAA…" />
        </a-form-item>
      </a-form>
    </a-modal>
    <a-table
      :loading="loading"
      :columns="columns"
      :dataSource="items"
      :rowSelection="rowSelection"
      :rowKey="item => item.name"
      :pagination="false"
      size="middle"
      :scroll="{ y: 225 }">
      <template #headerCell="{ column }">
        <template v-if="column.key === 'account'"><user-outlined /> {{ $t('label.account') }}</template>
        <template v-if="column.key === 'domain'"><block-outlined /> {{ $t('label.domain') }}</template>
      </template>
    </a-table>
    <div style="display: block; text-align: right;">
      <a-pagination
        size="small"
        :current="options.page"
        :pageSize="options.pageSize"
        :total="rowCount"
        :showTotal="total => `${$t('label.total')} ${total} ${$t('label.items')}`"
        :pageSizeOptions="['10', '20', '40', '80', '100', '200']"
        @change="onChangePage"
        @showSizeChange="onChangePageSize"
        showSizeChanger>
        <template #buildOptionText="props">
          <span>{{ props.value }} / {{ $t('label.page') }}</span>
        </template>
      </a-pagination>
    </div>
  </div>
</template>

<script>
import { postAPI } from '@/api'

export default {
  name: 'SshKeyPairSelection',
  props: {
    owner: {
      type: Object,
      default: () => ({})
    },
    items: {
      type: Array,
      default: () => []
    },
    rowCount: {
      type: Number,
      default: () => 0
    },
    value: {
      type: Array,
      default: () => []
    },
    loading: {
      type: Boolean,
      default: false
    },
    preFillContent: {
      type: Object,
      default: () => ({})
    },
    zoneId: {
      type: String,
      default: () => ''
    }
  },
  data () {
    return {
      filter: '',
      showAddKey: false,
      savingKey: false,
      keyName: '',
      publicKey: '',
      columns: [
        {
          key: 'name',
          dataIndex: 'name',
          title: this.$t('label.sshkeypairs'),
          width: '40%'
        },
        {
          key: 'account',
          dataIndex: 'account',
          width: '30%'
        },
        {
          key: 'domain',
          dataIndex: 'domain',
          width: '30%'
        }
      ],
      selectedRowKeys: [],
      oldZoneId: null,
      options: {
        page: 1,
        pageSize: 10,
        keyword: null
      }
    }
  },
  computed: {
    rowSelection () {
      return {
        type: 'checkbox',
        selectedRowKeys: this.value,
        preserveSelectedRowKeys: true,
        onChange: (selectedRowKeys) => {
          this.$emit('select-ssh-key-pair-item', selectedRowKeys.map(name => ({ name })))
        }
      }
    }
  },
  watch: {
    value (newValue, oldValue) {
      if (newValue && newValue !== oldValue) {
        this.selectedRowKeys = newValue
      }
    },
    loading () {
      if (!this.loading && this.oldZoneId !== this.zoneId) {
        this.oldZoneId = this.zoneId
        const keys = this.preFillContent.keypairs || []
        this.selectedRowKeys = keys.map(key => typeof key === 'string' ? key : key.name)
        this.$emit('select-ssh-key-pair-item', this.selectedRowKeys.map(name => ({ name })))
      }
    }
  },
  methods: {
    async addKey () {
      if (this.savingKey) return
      if (!this.keyName.trim() || !this.publicKey.trim()) {
        this.$message.error(this.$t('message.ssh.key.required'))
        return
      }
      this.savingKey = true
      try {
        const projectid = this.$store.getters.project?.id || this.owner.projectid
        const params = { name: this.keyName.trim(), publickey: this.publicKey.trim() }
        if (projectid) {
          params.projectid = projectid
        } else {
          if (this.owner.account) params.account = this.owner.account
          if (this.owner.domainid) params.domainid = this.owner.domainid
        }
        await postAPI('registerSSHKeyPair', params)
        this.$emit('select-ssh-key-pair-item', [...new Set([...this.value, params.name])].map(name => ({ name })))
        this.$message.success(this.$t('message.success.register.keypair') + ' ' + params.name)
        this.showAddKey = false
        this.keyName = ''
        this.publicKey = ''
        this.handleSearch('')
      } catch (error) {
        this.$notifyError(error)
      } finally {
        this.savingKey = false
      }
    },
    handleSearch (value) {
      this.filter = value
      this.options.page = 1
      this.options.pageSize = 10
      this.options.keyword = this.filter
      this.$emit('handle-search-filter', this.options)
    },
    onChangePage (page, pageSize) {
      this.options.page = page
      this.options.pageSize = pageSize
      this.$emit('handle-search-filter', this.options)
    },
    onChangePageSize (page, pageSize) {
      this.options.page = page
      this.options.pageSize = pageSize
      this.$emit('handle-search-filter', this.options)
    }
  }
}
</script>

<style lang="less" scoped>
  .ssh-toolbar {
    display: flex;
    flex-wrap: wrap;
    justify-content: flex-end;
    gap: 5px;
    margin-bottom: 10px;

    .ant-input-search {
      width: 25vw;
      max-width: 100%;
    }
  }

  .ant-table-wrapper {
    margin: 0 0 16px;
  }

  :deep(.ant-table-tbody) > tr > td {
    cursor: pointer;
  }
</style>
