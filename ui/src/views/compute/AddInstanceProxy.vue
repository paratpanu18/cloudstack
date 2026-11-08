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
  <div class="form" v-ctrl-enter="submitData">

    <div v-if="loading" class="loading">
      <loading-outlined :style="{ color: $config.theme['@primary-color'] }" />
    </div>

    <div class="form__item">
      <p class="form__label">{{ $t('label.proxy.domain') }}<span class="form__required" :aria-label="$t('label.required')">*</span></p>
      <a-select
        v-model:value="proxyDomainId"
        :options="proxyDomainOptions"
        :loading="loadingDomains"
        style="width: 100%"
        @change="handleDomainChange"
        :placeholder="$t('label.proxy.domain')" />
      <div class="form__hint" v-if="proxyDomains.length === 0 && !loadingDomains">
        {{ $t('message.proxy.domain.none.available') }}
      </div>
    </div>

    <a-alert v-if="!allowCustomName" type="info" :message="$t('message.proxy.random.only')" style="margin-bottom: 15px" />
    <div v-else class="form__item">
      <p class="form__label">{{ $t('label.proxy.name') }}</p>
      <a-input
        v-model:value="name"
        :placeholder="$t('label.proxy.name.placeholder')"
        @input="handleNameInput">
        <template #addonAfter>
          <span v-if="proxyDomain" class="proxy-fqdn-suffix">.{{ proxyDomain }}</span>
          <a-tooltip :title="$t('label.proxy.random')">
            <a-button type="text" size="small" class="proxy-random-btn" @click="randomizeName">
              <template #icon><sync-outlined /></template>
            </a-button>
          </a-tooltip>
        </template>
      </a-input>
      <div class="form__hint" v-if="checkingName">
        {{ $t('label.proxy.checking') }}
      </div>
      <a-alert
        v-else-if="nameChecked && !nameAvailable"
        type="error"
        style="margin-top: 5px">
        <template #message>{{ nameCheckMessage }}</template>
      </a-alert>
    </div>

    <a-divider class="proxy-section-divider" />

    <div class="form__item">
      <p class="form__label">{{ $t('label.network') }}<span class="form__required" :aria-label="$t('label.required')">*</span></p>
      <a-select
        v-model:value="networkId"
        :options="networkOptions"
        :loading="loadingNetworks"
        style="width: 100%"
        @change="fetchPublicIps" />
    </div>

    <div v-if="isIsolated" class="proxy-fields-row">
      <div class="form__item">
        <p class="form__label">
          {{ $t('label.public.ip') }}
          <span class="form__required" :aria-label="$t('label.required')">*</span>
          <a-tooltip :title="$t('message.proxy.port.forwarding')">
            <button type="button" class="proxy-help" :aria-label="$t('message.proxy.port.forwarding')">
              <question-circle-outlined />
            </button>
          </a-tooltip>
        </p>
        <a-select v-model:value="publicIpId" :options="publicIpOptions" :loading="loadingPublicIps" style="width: 100%" />
        <div v-if="!loadingPublicIps && publicIpOptions.length === 0" class="form__hint">
          {{ $t('message.proxy.public.ip.required') }}
        </div>
      </div>
      <div class="form__item">
        <p class="form__label">{{ $t('label.publicport') }}<span class="form__required" :aria-label="$t('label.required')">*</span></p>
        <a-input-number v-model:value="publicPort" :min="1" :max="65535" style="width: 100%" />
      </div>
    </div>

    <a-divider class="proxy-section-divider" />

    <div class="proxy-fields-row">
      <div class="form__item">
        <p class="form__label">{{ $t('label.proxy.protocol') }}<span class="form__required" :aria-label="$t('label.required')">*</span></p>
        <a-radio-group v-model:value="protocol">
          <a-radio value="http">http</a-radio>
          <a-radio value="https">https</a-radio>
        </a-radio-group>
      </div>

      <div class="form__item">
        <p class="form__label">{{ $t('label.proxy.port') }}<span class="form__required" :aria-label="$t('label.required')">*</span></p>
        <a-input-number
          v-model:value="port"
          :min="1"
          :max="65535"
          style="width: 100%" />
      </div>

    </div>

    <div class="submit-btn">
      <a-button @click="closeAction">
        {{ $t('label.cancel') }}
      </a-button>
      <a-button type="primary" @click="submitData" ref="submit" :disabled="loading || loadingDomains || loadingNetworks || loadingPublicIps || checkingName || (allowCustomName && nameChecked && !nameAvailable) || !proxyDomainId || !networkId || (isIsolated && !publicIpId)">
        {{ $t('label.ok') }}
      </a-button>
    </div>

  </div>
</template>

<script>
import { getAPI, postAPI } from '@/api'
import { waitForProxyJob } from '@/utils/reverseproxy'

const RANDOM_ADJECTIVES = ['swift', 'bright', 'calm', 'brave', 'clever', 'eager', 'gentle', 'happy',
  'jolly', 'kind', 'lively', 'mellow', 'noble', 'quiet', 'rapid', 'sunny', 'tidy', 'vivid', 'wise', 'bold']
const RANDOM_NOUNS = ['falcon', 'tiger', 'panda', 'otter', 'lynx', 'maple', 'willow', 'cedar', 'quartz',
  'zephyr', 'harbor', 'meadow', 'summit', 'river', 'forest', 'ember', 'comet', 'orbit', 'delta', 'coral']

export default {
  name: 'AddInstanceProxy',
  props: {
    resource: {
      type: Object,
      required: true
    }
  },
  inject: ['parentFetchData'],
  data () {
    return {
      nics: [],
      networkId: undefined,
      defaultNetworkId: undefined,
      loadingNetworks: false,
      publicIpId: undefined,
      publicPort: 80,
      publicIpOptions: [],
      loadingPublicIps: false,
      publicIpRequest: 0,
      nameCheckRequest: 0,
      name: '',
      protocol: 'http',
      port: 80,
      proxyDomains: [],
      proxyDomainId: undefined,
      proxyDomain: '',
      loadingDomains: false,
      checkingName: false,
      nameChecked: false,
      nameAvailable: false,
      nameCheckMessage: '',
      checkTimer: null,
      loading: false,
      firstActivated: true
    }
  },
  computed: {
    allowCustomName () {
      return this.proxyDomains.find(domain => domain.id === this.proxyDomainId)?.allowcustomname === true
    },
    networkOptions () {
      return this.nics.map(nic => ({ label: `${nic.networkname || nic.networkid} (${nic.type})`, value: nic.networkid }))
    },
    isIsolated () {
      return this.nics.find(nic => nic.networkid === this.networkId)?.type === 'Isolated'
    },
    proxyDomainOptions () {
      return this.proxyDomains.map(domain => {
        return { label: domain.domain, value: domain.id }
      })
    }
  },
  mounted () {
    this.fetchNetworks()
    this.fetchProxyDomains()
  },
  activated () {
    if (this.firstActivated) {
      // The initial activation, mounted() has already fetched the proxy domains
      this.firstActivated = false
      return
    }
    // The action modal keeps this component alive (keep-alive), reset the form
    // so that a stale name or availability check is not shown when reopened
    this.resetForm()
    this.fetchNetworks()
    this.fetchProxyDomains()
  },
  beforeUnmount () {
    if (this.checkTimer) {
      clearTimeout(this.checkTimer)
    }
  },
  methods: {
    resetForm () {
      if (this.checkTimer) {
        clearTimeout(this.checkTimer)
      }
      this.checkTimer = null
      this.publicIpRequest++
      this.nameCheckRequest++
      this.networkId = undefined
      this.defaultNetworkId = undefined
      this.publicIpId = undefined
      this.publicPort = 80
      this.publicIpOptions = []
      this.name = ''
      this.protocol = 'http'
      this.port = 80
      this.checkingName = false
      this.nameChecked = false
      this.nameAvailable = false
      this.nameCheckMessage = ''
      this.loading = false
    },
    fetchNetworks () {
      this.loadingNetworks = true
      return getAPI('listNics', { virtualmachineid: this.resource.id }).then(response => {
        this.nics = (response.listnicsresponse.nic || []).filter(nic => ['Shared', 'Isolated'].includes(nic.type) && nic.ipaddress)
        this.selectDefaultNetwork()
      }).catch(error => this.$notifyError(error)).finally(() => { this.loadingNetworks = false })
    },
    selectDefaultNetwork () {
      if (!this.networkId && this.defaultNetworkId && this.nics.some(nic => nic.networkid === this.defaultNetworkId)) {
        this.networkId = this.defaultNetworkId
        this.fetchPublicIps()
      }
    },
    async fetchPublicIps () {
      const request = ++this.publicIpRequest
      const networkId = this.networkId
      this.publicIpId = undefined
      this.publicIpOptions = []
      this.loadingPublicIps = false
      if (!this.isIsolated) return
      this.loadingPublicIps = true
      try {
        const networkResponse = await getAPI('listNetworks', { id: networkId })
        const network = networkResponse.listnetworksresponse.network?.[0]
        if (!network) throw new Error(this.$t('message.proxy.public.ip.required'))
        const params = { allocatedonly: true, isstaticnat: false, listall: true }
        if (network.vpcid) params.vpcid = network.vpcid
        else params.associatednetworkid = networkId
        if (this.resource.projectid) params.projectid = this.resource.projectid
        const response = await getAPI('listPublicIpAddresses', params)
        if (request !== this.publicIpRequest) return
        this.publicIpOptions = (response.listpublicipaddressesresponse.publicipaddress || [])
          .filter(ip => ip.state === 'Allocated' && !ip.isstaticnat && (!network.vpcid || !ip.issourcenat) &&
            (!ip.associatednetworkid || ip.associatednetworkid === networkId) &&
            (this.resource.projectid ? ip.projectid === this.resource.projectid : ip.account === this.resource.account && ip.domainid === this.resource.domainid))
          .map(ip => ({ label: ip.ipaddress, value: ip.id }))
        this.publicIpId = this.publicIpOptions[0]?.value
      } catch (error) {
        if (request === this.publicIpRequest) this.$notifyError(error)
      } finally {
        if (request === this.publicIpRequest) this.loadingPublicIps = false
      }
    },
    fetchProxyDomains () {
      if (!('listReverseProxyDomains' in this.$store.getters.apis)) {
        return
      }
      this.loadingDomains = true
      getAPI('listReverseProxyDomains', {
        virtualmachineid: this.resource.id
      }).then(response => {
        this.proxyDomains = response.listreverseproxydomainsresponse.reverseproxydomain || []
        if (this.proxyDomains.length > 0) {
          this.proxyDomainId = this.proxyDomains[0].id
          this.proxyDomain = this.proxyDomains[0].domain
          this.defaultNetworkId = this.proxyDomains[0].defaultnetworkid
          this.selectDefaultNetwork()
        } else {
          this.proxyDomainId = undefined
          this.proxyDomain = ''
        }
      }).catch(() => {}).finally(() => {
        this.loadingDomains = false
      })
    },
    handleDomainChange () {
      if (this.checkTimer) clearTimeout(this.checkTimer)
      this.checkingName = false
      const selected = this.proxyDomains.find(domain => domain.id === this.proxyDomainId)
      this.proxyDomain = selected ? selected.domain : ''
      this.nameCheckRequest++
      if (!this.allowCustomName) this.name = ''
      this.nameChecked = false
      this.nameAvailable = false
      this.nameCheckMessage = ''
      if (this.allowCustomName && this.name && this.name.trim()) {
        this.handleNameInput()
      }
    },
    randomizeName () {
      const pick = (arr) => arr[Math.floor(Math.random() * arr.length)]
      this.name = pick(RANDOM_ADJECTIVES) + '-' + pick(RANDOM_NOUNS) + '-' + Math.floor(100 + Math.random() * 900)
      this.handleNameInput()
    },
    handleNameInput () {
      this.nameCheckRequest++
      this.checkingName = false
      this.nameChecked = false
      this.nameAvailable = false
      this.nameCheckMessage = ''
      if (this.checkTimer) {
        clearTimeout(this.checkTimer)
      }
      if (!this.allowCustomName || !this.name || !this.name.trim()) {
        return
      }
      if (!('checkInstanceProxyName' in this.$store.getters.apis)) {
        return
      }
      this.checkTimer = setTimeout(() => {
        this.checkName()
      }, 600)
    },
    checkName () {
      if (!this.allowCustomName) return
      const request = ++this.nameCheckRequest
      this.checkingName = true
      postAPI('checkInstanceProxyName', {
        name: this.name.trim(),
        virtualmachineid: this.resource.id,
        domainid: this.proxyDomainId
      }).then(response => {
        if (request !== this.nameCheckRequest) return
        const result = response.checkinstanceproxynameresponse?.instanceproxyname || {}
        this.nameChecked = true
        this.nameAvailable = !!result.available
        this.nameCheckMessage = result.message || ''
      }).catch(error => {
        if (request !== this.nameCheckRequest) return
        this.nameChecked = false
        this.$notifyError(error)
      }).finally(() => {
        if (request === this.nameCheckRequest) this.checkingName = false
      })
    },
    closeAction () {
      this.$emit('close-action')
    },
    submitData () {
      if (this.loading || this.loadingDomains || this.loadingNetworks || this.loadingPublicIps || this.checkingName ||
        (this.allowCustomName && this.nameChecked && !this.nameAvailable)) return
      if (!this.proxyDomainId) {
        this.$notification.error({
          message: this.$t('label.proxy.domain'),
          description: this.$t('message.proxy.domain.none.available')
        })
        return
      }
      if (!this.networkId || (this.isIsolated && (!this.publicIpId || !this.publicPort || this.publicPort < 1 || this.publicPort > 65535))) {
        this.$notification.error({ message: this.$t('label.reverseproxy'), description: this.$t('message.proxy.public.ip.required') })
        return
      }
      if (!this.port || this.port < 1 || this.port > 65535) {
        this.$notification.error({
          message: this.$t('label.proxy.port'),
          description: this.$t('message.proxy.port.invalid')
        })
        return
      }
      this.loading = true
      postAPI('addInstanceProxy', {
        virtualmachineid: this.resource.id,
        ...(this.allowCustomName && this.name.trim() ? { name: this.name.trim() } : {}),
        domainid: this.proxyDomainId,
        networkid: this.networkId,
        ...(this.isIsolated ? { ipaddressid: this.publicIpId, publicport: this.publicPort } : {}),
        protocol: this.protocol,
        port: this.port
      }).then(response => waitForProxyJob(this, response, 'addinstanceproxyresponse')).then(() => {
        this.$notification.success({
          message: this.$t('label.reverseproxy'),
          description: this.$t('message.success.add.instance.proxy')
        })
        this.$emit('close-action')
        if (this.parentFetchData) {
          this.parentFetchData()
        }
      }).catch(error => {
        this.$notifyError(error)
      }).finally(() => {
        this.loading = false
      })
    }
  }
}
</script>

<style scoped lang="scss">
  .form {
    width: 85vw;

    @media (min-width: 760px) {
      width: 500px;
    }

    display: flex;
    flex-direction: column;

    &__item {
      display: flex;
      flex-direction: column;
      width: 100%;
      margin-bottom: 10px;
    }

    &__required {
      margin-left: 4px;
      color: #ff4d4f;
    }

    &__label {
      display: flex;
      font-weight: bold;
      margin-bottom: 5px;
    }

  &__hint {
    margin-top: 5px;
    color: rgba(0, 0, 0, 0.45);
  }

  .proxy-fqdn-suffix {
    margin-right: 4px;
    white-space: nowrap;
    user-select: all;
  }

  .proxy-random-btn {
    padding: 0 4px;
    height: auto;
  }
  }

  .proxy-section-divider {
    margin: 6px 0 14px;
  }

  .proxy-fields-row {
    display: grid;
    grid-template-columns: minmax(0, 2fr) minmax(0, 1fr);
    gap: 16px;

    @media (max-width: 480px) {
      grid-template-columns: minmax(0, 1fr);
      gap: 0;
    }
  }

  .proxy-help {
    display: inline-flex;
    align-items: center;
    margin-left: 6px;
    padding: 0;
    border: 0;
    background: transparent;
    color: inherit;
    cursor: help;
  }

  .submit-btn {
    margin-top: 10px;
    align-self: flex-end;

    button {
      margin-left: 10px;
    }
  }

  .loading {
    position: absolute;
    top: 0;
    right: 0;
    bottom: 0;
    left: 0;
    z-index: 1;
    display: flex;
    align-items: center;
    justify-content: center;
    font-size: 3rem;
  }
</style>
