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
    <div class="form__item">
      <label class="form__label" for="proxy-edit-name">{{ $t('label.proxy.name') }}<span class="form__required">*</span></label>
      <a-tooltip :title="!allowCustomName && !loadingPolicy ? $t('message.proxy.random.only') : undefined">
        <span class="proxy-edit-input">
          <a-input
            id="proxy-edit-name"
            v-model:value="name"
            :disabled="loading || loadingPolicy || !allowCustomName || generateName"
            :placeholder="generateName ? $t('label.proxy.name.placeholder') : undefined"
            :maxLength="63"
            @pressEnter="save">
            <template #addonAfter>.{{ suffix }}</template>
          </a-input>
        </span>
      </a-tooltip>
      <a-checkbox v-model:checked="generateName" :disabled="loading || loadingPolicy" class="proxy-edit-random">{{ $t('label.proxy.generate.random') }}</a-checkbox>
    </div>
    <div class="proxy-edit-backend">
      <div>
        <p class="form__label">{{ $t('label.proxy.protocol') }}<span class="form__required">*</span></p>
        <a-radio-group v-model:value="protocol" :disabled="loading" :aria-label="$t('label.proxy.protocol')">
          <a-radio-button value="http">HTTP</a-radio-button>
          <a-radio-button value="https">HTTPS</a-radio-button>
        </a-radio-group>
      </div>
      <div>
        <label class="form__label" for="proxy-edit-port">{{ $t('label.proxy.port') }}<span class="form__required">*</span></label>
        <a-input-number
          id="proxy-edit-port"
          v-model:value="port"
          :min="1"
          :max="65535"
          :disabled="loading"
          style="width: 100%" />
      </div>
    </div>
    <a-alert v-if="error" type="error" :message="error" show-icon class="proxy-edit-error" />
    <div class="proxy-edit-actions">
      <a-button :disabled="loading" @click="$emit('close-action')">{{ $t('label.cancel') }}</a-button>
      <a-button type="primary" :loading="loading" :disabled="!canSave" @click="save">{{ $t('label.save') }}</a-button>
    </div>
  </div>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import { waitForProxyJob } from '@/utils/reverseproxy'

export default {
  name: 'EditInstanceProxy',
  props: {
    resource: { type: Object, required: true },
    proxy: { type: Object, required: true }
  },
  emits: ['close-action', 'saved', 'busy'],
  data () {
    return { name: this.proxy.name, protocol: this.proxy.protocol, port: this.proxy.port, generateName: false, allowCustomName: false, loadingPolicy: true, policyFailed: false, loading: false, error: '' }
  },
  computed: {
    suffix () { return this.proxy.fqdn.slice(this.proxy.name.length + 1) },
    canSave () {
      const nameValid = this.generateName || this.name === this.proxy.name || (this.allowCustomName && !!this.name.trim())
      const changed = this.generateName || this.name.trim() !== this.proxy.name || this.protocol !== this.proxy.protocol || this.port !== this.proxy.port
      return !this.loading && !this.loadingPolicy && !this.policyFailed && nameValid && changed &&
        ['http', 'https'].includes(this.protocol) && Number.isInteger(this.port) && this.port >= 1 && this.port <= 65535
    }
  },
  watch: {
    generateName (enabled) { this.name = enabled ? '' : this.proxy.name },
    protocol (value, previous) {
      if (this.port === (previous === 'https' ? 443 : 80)) this.port = value === 'https' ? 443 : 80
    }
  },
  mounted () {
    getAPI('listReverseProxyDomains', { virtualmachineid: this.resource.id }).then(response => {
      const domain = (response.listreverseproxydomainsresponse.reverseproxydomain || []).find(domain => domain.domain === this.suffix)
      this.allowCustomName = domain?.allowcustomname === true
      this.policyFailed = !domain
    }).catch(error => { this.policyFailed = true; this.$notifyError(error) }).finally(() => { this.loadingPolicy = false })
  },
  methods: {
    async save () {
      if (!this.canSave) return
      this.error = ''
      this.loading = true
      this.$emit('busy', true)
      try {
        const response = await postAPI('updateInstanceProxy', {
          id: this.proxy.id,
          ...(this.generateName ? { generatename: true } : this.name.trim() !== this.proxy.name ? { name: this.name.trim() } : {}),
          protocol: this.protocol,
          port: this.port
        })
        await waitForProxyJob(this, response, 'updateinstanceproxyresponse')
        this.$notification.success({ message: this.$t('label.reverseproxy'), description: this.$t('message.success.update.instance.proxy') })
        this.$emit('saved')
      } catch (error) {
        this.error = error?.jobresult?.errortext || error?.response?.data?.errorresponse?.errortext || error?.message || this.$t('message.update.instance.proxy.failed')
      } finally {
        this.loading = false
        this.$emit('busy', false)
      }
    }
  }
}
</script>

<style scoped lang="scss">
.form__item { margin-bottom: 16px; }
.form__label { display: block; font-weight: 600; margin-bottom: 5px; }
.form__required { margin-left: 4px; color: #ff4d4f; }
.proxy-edit-input { display: block; }
.proxy-edit-random { margin-top: 8px; }
.proxy-edit-backend { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-bottom: 16px; }
.proxy-edit-error { margin-bottom: 16px; }
.proxy-edit-actions { display: flex; justify-content: flex-end; gap: 10px; }
</style>
