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
  <div class="login-container">
    <!-- Subtle pulsing background glow -->
    <div class="bg-pulse" aria-hidden="true">
      <span class="sweep"></span>
      <span class="pulse-glow g1"></span>
      <span class="pulse-glow g2"></span>
      <span class="pulse-glow g3"></span>
    </div>

    <!-- Login Form -->
    <div class="login-form-container">
      <div class="login-form-wrapper">
        <div class="login-header">
          <img :src="logo" alt="Logo" class="brand-logo" />
        </div>

        <a-form
          id="formLogin"
          class="user-layout-login"
          :ref="formRef"
          :model="form"
          :rules="rules"
          @finish="handleSubmit"
          v-ctrl-enter="handleSubmit"
        >
          <a-form-item ref="domain" name="domain">
            <div class="field-label">{{ $t('label.domain') }}</div>
            <a-auto-complete
              class="domain-select"
              v-model:value="form.domain"
              :options="domainOptions"
              dropdownClassName="login-dropdown"
              :filter-option="false"
              backfill
              @search="onDomainSearch"
              @select="onDomainSelect"
              @dropdown-visible-change="onDomainDropdownVisible"
            >
              <a-input
                size="large"
                :placeholder="$t('label.domain')"
              >
                <template #prefix>
                  <project-outlined />
                </template>
              </a-input>
            </a-auto-complete>
          </a-form-item>
          <a-form-item v-if="$config.multipleServer" name="server" ref="server">
            <a-select
              size="large"
              :placeholder="$t('server')"
              v-model:value="form.server"
              dropdownClassName="login-dropdown"
              @change="onChangeServer"
              showSearch
              optionFilterProp="label"
              :filterOption="(input, option) => {
                return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
              }">
              <a-select-option v-for="item in $config.servers" :key="(item.apiHost || '') + item.apiBase" :label="item.name">
                <template #prefix>
                  <database-outlined />
                </template>
                {{ item.name }}
              </a-select-option>
            </a-select>
          </a-form-item>
          <a-form-item ref="username" name="username">
            <a-input
              size="large"
              type="text"
              v-focus="true"
              :placeholder="$t('label.username')"
              v-model:value="form.username"
            >
              <template #prefix>
                <user-outlined />
              </template>
            </a-input>
          </a-form-item>
          <a-form-item ref="password" name="password">
            <a-input-password
              size="large"
              type="password"
              autocomplete="false"
              :placeholder="$t('label.password')"
              v-model:value="form.password"
            >
              <template #prefix>
                <lock-outlined />
              </template>
            </a-input-password>
          </a-form-item>
          <a-form-item ref="project" name="project" v-if="$config.displayProjectFieldOnLogin">
            <a-input
              size="large"
              type="text"
              :placeholder="$t('label.project')"
              v-model:value="form.project"
            >
              <template #prefix>
                <block-outlined />
              </template>
            </a-input>
          </a-form-item>

          <a-form-item>
            <a-button
              size="large"
              type="primary"
              html-type="submit"
              class="login-button"
              :loading="state.loginBtn"
              :disabled="state.loginBtn"
              ref="submit"
              @click="handleSubmit"
            >{{ $t('label.login') }}</a-button>
          </a-form-item>
          <a-row justify="space-between">
            <!-- <a-col>
            <translation-menu/>
            </a-col> -->
            <a-col v-if="forgotPasswordEnabled">
              <router-link :to="{ name: 'forgotPassword' }" class="forgot-password-link">
                {{ $t('label.forgot.password') }}
              </router-link>
            </a-col>
          </a-row>
          <div class="sso-section" v-if="idps.length > 0">
            <p class="or">{{ $t('label.or.sign.in.with') }}</p>
            <a-form-item name="idp" ref="idp">
              <a-select
                v-model:value="form.idp"
                size="large"
                dropdownClassName="login-dropdown"
                showSearch
                optionFilterProp="label"
                :placeholder="$t('label.login.single.signon')"
                :filterOption="(input, option) => {
                  return option.label.toLowerCase().indexOf(input.toLowerCase()) >= 0
                }" >
                <a-select-option v-for="(idp, idx) in idps" :key="idx" :value="idp.id" :label="idp.orgName">
                  {{ idp.orgName }}
                </a-select-option>
              </a-select>
            </a-form-item>
            <a-button
              size="large"
              block
              class="sso-button"
              @click="handleSamlLogin"
            >
              <audit-outlined />
              <span>{{ $t('label.login.single.signon') }}</span>
            </a-button>
          </div>
          <div class="content" v-if="socialLogin">
            <p class="or">{{ $t('label.or.sign.in.with') }}</p>
          </div>
          <div v-if="socialLogin" class="oauth-section">
            <a-button
              v-for="(btn, idx) in oauthButtons"
              :key="btn.key"
              size="large"
              block
              :type="idx === 0 ? 'primary' : 'default'"
              class="oauth-login-button"
              :href="btn.url"
              @click="btn.click()"
            >
              <img v-if="btn.img" :src="btn.img" class="oauth-btn-logo" />
              <span>{{ btn.label }}</span>
            </a-button>
          </div>
        </a-form>

        <div class="login-footer" v-if="loginFooter" v-html="loginFooter"></div>
        <div class="login-footer" v-else v-html="naclFooter"></div>
      </div>
    </div>
  </div>
</template>

<script>
import { ref, reactive, toRaw } from 'vue'
import { getAPI, postAPI } from '@/api'
import store from '@/store'
import { mapActions } from 'vuex'
import { sourceToken } from '@/utils/request'
import { SERVER_MANAGER, LAST_SELECTED_DOMAIN } from '@/store/mutation-types'
import { setStore, getStore } from '@/utils/storage'
import TranslationMenu from '@/components/header/TranslationMenu'

export default {
  components: {
    TranslationMenu
  },
  data () {
    return {
      idps: [],
      loginBtn: false,
      email: '',
      secretcode: '',
      oauthexclude: '',
      socialLogin: false,
      naclFooter: 'Made with 💙 by <a href="https://www.ce-nacl.com" target="_blank" rel="noopener">Network and Cloud Laboratory (NaCl)</a><br>Department of Computer Engineering, KMITL',
      googleprovider: false,
      githubprovider: false,
      keycloakprovider: false,
      keycloakname: '',
      googlelogo: '',
      githublogo: '',
      keycloaklogo: '',
      googleredirecturi: '',
      githubredirecturi: '',
      keycloakredirecturi: '',
      googleclientid: '',
      githubclientid: '',
      keycloakclientid: '',
      keycloakauthorizeurl: '',
      loginType: 0,
      state: {
        time: 60,
        loginBtn: false,
        loginType: 0
      },
      server: '',
      forgotPasswordEnabled: false,
      project: null,
      loginDomains: [],
      domainKeyword: ''
    }
  },
  computed: {
    logo () {
      return this.$config.logo || 'assets/logo.svg'
    },
    appTitle () {
      return this.$config.loginTitle || this.$config.appTitle || 'CloudStack'
    },
    loginFooter () {
      return this.$config.loginFooter || ''
    },
    domainOptions () {
      const all = this.loginDomains.map(d => {
        const label = d.displayname || d.name
        return { value: label, label: label }
      })
      const keyword = (this.domainKeyword || '').toLowerCase()
      if (!keyword) {
        return all
      }
      return all.filter(option => option.label.toLowerCase().indexOf(keyword) >= 0)
    },
    oauthButtons () {
      const buttons = []
      if (this.keycloakprovider) {
        buttons.push({
          key: 'keycloak',
          label: this.$t('label.login.with.name', { name: this.keycloakname || 'Keycloak' }),
          img: this.keycloaklogo || '',
          url: this.getKeycloakUrl(this.from),
          click: this.handleKeycloakProviderAndDomain
        })
      }
      if (this.googleprovider) {
        buttons.push({
          key: 'google',
          label: this.$t('label.login.with.name', { name: 'Google' }),
          img: this.googlelogo || '/assets/google.svg',
          url: this.getGoogleUrl(),
          click: this.handleGoogleProviderAndDomain
        })
      }
      if (this.githubprovider) {
        buttons.push({
          key: 'github',
          label: this.$t('label.login.with.name', { name: 'GitHub' }),
          img: this.githublogo || '/assets/github.svg',
          url: this.getGitHubUrl(this.from),
          click: this.handleGithubProviderAndDomain
        })
      }
      return buttons
    }
  },
  watch: {
    'form.username' (val) {
      this.applyLastSelectedDomain(val)
    }
  },
  created () {
    if (this.$config.multipleServer) {
      this.server = this.$localStorage.get(SERVER_MANAGER) || this.$config.servers[0]
    }
    this.initForm()
    if (store.getters.logoutFlag) {
      if (store.getters.readyForShutdownPollingJob !== '' || store.getters.readyForShutdownPollingJob !== undefined) {
        clearInterval(store.getters.readyForShutdownPollingJob)
      }
      sourceToken.init()
      this.fetchData()
    } else {
      this.fetchData()
    }
  },
  methods: {
    ...mapActions(['Login', 'Logout', 'OauthLogin']),
    initForm () {
      this.formRef = ref()
      const savedDomain = getStore(LAST_SELECTED_DOMAIN)
      this.form = reactive({
        server: (this.server.apiHost || '') + this.server.apiBase,
        username: this.$route.query?.username || '',
        domain: this.$route.query?.domain || savedDomain || '',
        project: null
      })
      this.rules = reactive({})
      this.setRules()
      this.applyLastSelectedDomain(this.form.username)
    },
    applyLastSelectedDomain (username) {
      if (!username || this.$route.query?.domain) {
        return
      }
      const savedDomain = getStore(LAST_SELECTED_DOMAIN + '_' + username) || getStore(LAST_SELECTED_DOMAIN)
      if (savedDomain) {
        this.form.domain = savedDomain
      }
    },
    onDomainSearch (text) {
      this.domainKeyword = text || ''
    },
    onDomainSelect () {
      this.domainKeyword = ''
    },
    onDomainDropdownVisible (open) {
      if (open) {
        // always show the full list of domains when the dropdown opens
        this.domainKeyword = ''
      }
    },
    preselectFirstDomain () {
      if (!this.form.domain && this.loginDomains.length > 0) {
        const first = this.loginDomains[0]
        this.form.domain = first.displayname || first.name
      }
    },
    resolveDomain (domain) {
      if (!domain) {
        return domain
      }
      const match = this.loginDomains.find(d => (d.displayname || d.name) === domain)
      return match ? match.path : domain
    },
    setRules () {
      this.rules.username = [
        {
          required: true,
          message: this.$t('message.error.username'),
          trigger: 'change'
        },
        {
          validator: this.handleUsernameOrEmail,
          trigger: 'change'
        }
      ]
      this.rules.password = [
        {
          required: true,
          message: this.$t('message.error.password'),
          trigger: 'change'
        }
      ]
    },
    fetchData () {
      getAPI('listLoginDomains').then(response => {
        if (response) {
          this.loginDomains = response.listlogindomainsresponse.logindomain || []
          this.preselectFirstDomain()
        }
      }).catch(() => {
        this.loginDomains = []
      })
      getAPI('listIdps').then(response => {
        if (response) {
          this.idps = response.listidpsresponse.idp || []
          this.idps.sort(function (a, b) {
            if (a.orgName < b.orgName) { return -1 }
            if (a.orgName > b.orgName) { return 1 }
            return 0
          })
          this.form.idp = this.idps[0].id || ''
        }
      })
      getAPI('listOauthProvider', {}).then(response => {
        if (response) {
          const oauthproviders = response.listoauthproviderresponse.oauthprovider || []
          oauthproviders.forEach(item => {
            if (item.provider === 'google') {
              this.googleprovider = item.enabled
              this.googleclientid = item.clientid
              this.googleredirecturi = item.redirecturi
              this.googlelogo = item.logo || ''
            }
            if (item.provider === 'github') {
              this.githubprovider = item.enabled
              this.githubclientid = item.clientid
              this.githubredirecturi = item.redirecturi
              this.githublogo = item.logo || ''
            }
            if (item.provider === 'keycloak') {
              this.keycloakprovider = item.enabled
              this.keycloakclientid = item.clientid
              this.keycloakredirecturi = item.redirecturi
              this.keycloakauthorizeurl = item.authorizeurl
              this.keycloakname = item.description || 'Keycloak'
              this.keycloaklogo = item.logo || ''
            }
          })
          this.socialLogin = this.googleprovider || this.githubprovider || this.keycloakprovider
        }
      })
      postAPI('forgotPassword', {}).then(response => {
        this.forgotPasswordEnabled = response.forgotpasswordresponse.enabled
      }).catch((err) => {
        if (err?.response?.data === null) {
          this.forgotPasswordEnabled = true
        } else {
          this.forgotPasswordEnabled = false
        }
      })
    },
    // handler
    async handleUsernameOrEmail (rule, value) {
      const { state } = this
      const regex = /^([a-zA-Z0-9_-])+@([a-zA-Z0-9_-])+((\.[a-zA-Z0-9_-]{2,3}){1,2})$/
      if (regex.test(value)) {
        state.loginType = 0
      } else {
        state.loginType = 1
      }
      return Promise.resolve()
    },
    handleGithubProviderAndDomain () {
      this.handleDomain()
      this.$store.commit('SET_OAUTH_PROVIDER_USED_TO_LOGIN', 'github')
    },
    handleGoogleProviderAndDomain () {
      this.handleDomain()
      this.$store.commit('SET_OAUTH_PROVIDER_USED_TO_LOGIN', 'google')
    },
    handleKeycloakProviderAndDomain () {
      this.handleDomain()
      this.$store.commit('SET_OAUTH_PROVIDER_USED_TO_LOGIN', 'keycloak')
    },
    handleDomain () {
      const values = toRaw(this.form)
      if (!values.domain) {
        this.$store.commit('SET_DOMAIN_USED_TO_LOGIN', '/')
      } else {
        this.$store.commit('SET_DOMAIN_USED_TO_LOGIN', this.resolveDomain(values.domain))
      }
    },
    getGitHubUrl (from) {
      const rootURl = 'https://github.com/login/oauth/authorize'
      const options = {
        client_id: this.githubclientid,
        scope: 'user:email',
        state: 'cloudstack'
      }

      const qs = new URLSearchParams(options)

      return `${rootURl}?${qs.toString()}`
    },
    getGoogleUrl (from) {
      const rootUrl = 'https://accounts.google.com/o/oauth2/v2/auth'
      const options = {
        redirect_uri: this.googleredirecturi,
        client_id: this.googleclientid,
        access_type: 'offline',
        response_type: 'code',
        prompt: 'consent',
        scope: [
          'https://www.googleapis.com/auth/userinfo.profile',
          'https://www.googleapis.com/auth/userinfo.email'
        ].join(' '),
        state: 'cloudstack'
      }

      const qs = new URLSearchParams(options)

      return `${rootUrl}?${qs.toString()}`
    },
    getKeycloakUrl (from) {
      const rootURl = this.keycloakauthorizeurl
      const options = {
        redirect_uri: this.keycloakredirecturi,
        client_id: this.keycloakclientid,
        response_type: 'code',
        scope: 'openid email',
        state: 'cloudstack'
      }

      const qs = new URLSearchParams(options)

      return `${rootURl}?${qs.toString()}`
    },
    handleSubmit (e) {
      e.preventDefault()
      if (this.state.loginBtn) return
      this.formRef.value.validate().then(() => {
        this.state.loginBtn = true

        const values = toRaw(this.form)
        if (this.$config.multipleServer) {
          this.axios.defaults.baseURL = (this.server.apiHost || '') + this.server.apiBase
          store.dispatch('SetServer', this.server)
        }
        const loginParams = { ...values }
        delete loginParams.username
        loginParams[!this.state.loginType ? 'email' : 'username'] = values.username
        loginParams.password = values.password
        loginParams.domain = this.resolveDomain(values.domain)
        if (!loginParams.domain) {
          loginParams.domain = '/'
        }
        this.Login(loginParams)
          .then((res) => this.loginSuccess(res))
          .catch(err => {
            this.requestFailed(err)
            this.state.loginBtn = false
          })
      }).catch(error => {
        this.formRef.value.scrollToField(error.errorFields[0].name)
      })
    },
    handleSamlLogin () {
      var samlUrl = this.$config.apiBase + '?command=samlSso'
      const values = toRaw(this.form)
      if (values.idp) {
        samlUrl += ('&idpid=' + values.idp)
      }
      window.location.href = samlUrl
    },
    async loginSuccess (res) {
      this.$notification.destroy()
      this.$store.commit('SET_COUNT_NOTIFY', 0)
      // Save the selected domain to localStorage for next login (per user and globally)
      const values = toRaw(this.form)
      if (values.domain) {
        setStore(LAST_SELECTED_DOMAIN, values.domain)
        if (values.username) {
          setStore(LAST_SELECTED_DOMAIN + '_' + values.username, values.domain)
        }
      }
      if (store.getters.twoFaEnabled === true && store.getters.twoFaProvider !== '' && store.getters.twoFaProvider !== undefined) {
        this.$router.push({ path: '/verify2FA' }).catch(() => {})
      } else if (store.getters.twoFaEnabled === true && (store.getters.twoFaProvider === '' || store.getters.twoFaProvider === undefined)) {
        this.$router.push({ path: '/setup2FA' }).catch(() => {})
      } else {
        this.$store.commit('SET_LOGIN_FLAG', true)
        const values = toRaw(this.form)
        if (values.project) {
          await this.getProject(values.project)
          this.$store.dispatch('ProjectView', this.project.id)
          this.$store.dispatch('SetProject', this.project)
          this.$store.dispatch('ToggleTheme', this.project.id === undefined ? 'light' : 'dark')
        }
        this.$router.push({ path: '/dashboard' }).catch(() => {})
      }
    },
    getProject (projectName) {
      return new Promise((resolve, reject) => {
        getAPI('listProjects', {
          response: 'json',
          domainId: this.selectedDomain,
          details: 'min'
        }).then((response) => {
          const projects = response.listprojectsresponse.project
          this.project = projects.filter(project => project.name === projectName)?.[0] || null
          resolve(this.project)
        }).catch((error) => {
          this.$notifyError(error)
        }).finally(() => {
          this.loading = false
        })
      })
    },
    requestFailed (err) {
      if (err && err.response && err.response.data && err.response.data.loginresponse) {
        const error = err.response.data.loginresponse.errorcode + ': ' + err.response.data.loginresponse.errortext
        this.$message.error(`${this.$t('label.error')} ${error}`)
      } else if (err && err.response && err.response.data && err.response.data.oauthloginresponse) {
        const error = err.response.data.oauthloginresponse.errorcode + ': ' + err.response.data.oauthloginresponse.errortext
        this.$message.error(`${this.$t('label.error')} ${error}`)
      } else {
        this.$message.error(this.$t('message.login.failed'))
      }
    },
    onChangeServer (server) {
      const servers = this.$config.servers || []
      const serverFilter = servers.filter(ser => (ser.apiHost || '') + ser.apiBase === server)
      this.server = serverFilter[0] || {}
    }
  }
}
</script>

<style lang="less" scoped>
.login-container {
  position: relative;
  display: flex;
  min-height: 100vh;
  min-height: 100dvh;
  width: 100%;
  margin: 0;
  padding: 0;
  overflow: hidden;
  background: linear-gradient(180deg, #ffffff 0%, #fff7ef 100%);
}

.bg-pulse {
  position: absolute;
  inset: 0;
  overflow: hidden;
  pointer-events: none;
  z-index: 0;
}

.sweep {
  position: absolute;
  top: -40%;
  left: -35%;
  width: 70%;
  height: 180%;
  background: linear-gradient(100deg, rgba(246, 135, 31, 0) 32%, rgba(246, 135, 31, 0.08) 50%, rgba(246, 135, 31, 0) 68%);
  will-change: transform, opacity;
  animation: bg-sweep 14s ease-in-out infinite;
}

.pulse-glow {
  position: absolute;
  border-radius: 50%;
  will-change: transform, opacity;
  animation: bg-pulse 8s ease-in-out infinite;

  &.g1 {
    top: -18%;
    left: -10%;
    width: clamp(360px, 48vw, 720px);
    height: clamp(360px, 48vw, 720px);
    background: radial-gradient(circle at center, rgba(246, 135, 31, 0.3) 0%, rgba(246, 135, 31, 0.11) 45%, rgba(246, 135, 31, 0) 70%);
  }

  &.g2 {
    right: -12%;
    bottom: -20%;
    width: clamp(400px, 55vw, 820px);
    height: clamp(400px, 55vw, 820px);
    background: radial-gradient(circle at center, rgba(246, 135, 31, 0.24) 0%, rgba(246, 135, 31, 0.09) 45%, rgba(246, 135, 31, 0) 70%);
    animation-duration: 10s;
    animation-delay: -4s;
  }

  &.g3 {
    top: 34%;
    right: 18%;
    width: clamp(220px, 26vw, 380px);
    height: clamp(220px, 26vw, 380px);
    background: radial-gradient(circle at center, rgba(246, 135, 31, 0.18) 0%, rgba(246, 135, 31, 0.07) 45%, rgba(246, 135, 31, 0) 70%);
    animation-duration: 12s;
    animation-delay: -2.5s;
  }
}

@keyframes bg-pulse {
  0%, 100% {
    transform: scale(1) translate3d(0, 0, 0);
    opacity: 0.35;
  }
  50% {
    transform: scale(1.12) translate3d(4%, -3.5%, 0);
    opacity: 1;
  }
}

@keyframes bg-sweep {
  0%, 100% {
    transform: rotate(10deg) translate3d(-55%, 0, 0);
    opacity: 0;
  }
  50% {
    transform: rotate(10deg) translate3d(55%, 0, 0);
    opacity: 1;
  }
}

@media (max-width: 768px) {
  .pulse-glow {
    &.g1 {
      width: 280px;
      height: 280px;
    }

    &.g2 {
      width: 320px;
      height: 320px;
    }

    &.g3 {
      width: 200px;
      height: 200px;
    }
  }

  .sweep {
    display: none;
  }
}

@media (prefers-reduced-motion: reduce) {
  .pulse-glow,
  .sweep {
    animation: none;
  }
}

.login-form-container {
  position: relative;
  z-index: 1;
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  padding: 48px 40px;

  @media (max-width: 768px) {
    padding: 40px 24px;
  }
}

.login-form-wrapper {
  width: 100%;
  max-width: 420px;
}

.login-header {
  margin-bottom: 32px;
  text-align: center;

  .brand-logo {
    max-width: 520px;
    height: 120px;
    margin: 0 auto 10px;
    display: block;

    @media (max-width: 768px) {
      max-width: 280px;
      height: auto;
    }
  }
}

.login-footer {
  margin-top: 40px;
  padding-top: 24px;
  border-top: 1px solid #f0f0f0;
  text-align: center;
  font-size: 14px;
  color: #8c8c8c;

  :deep(a) {
    color: #f6871f;
    text-decoration: none;

    &:hover {
      text-decoration: underline;
    }
  }
}

.user-layout-login {
  width: 100%;

  :deep(.ant-form-item) {
    margin-bottom: 20px;
  }

  :deep(.ant-input-affix-wrapper) {
    padding: 10px 15px;
    border-radius: 8px;
    border: 1px solid #d9d9d9;

    &:hover {
      border-color: #fb9a3d;
    }

    &:focus,
    &.ant-input-affix-wrapper-focused {
      border-color: #f6871f;
      box-shadow: 0 0 0 2px rgba(246, 135, 31, 0.12);
    }

    .ant-input {
      border: none !important;
      box-shadow: none !important;

      &:focus {
        border: none !important;
        box-shadow: none !important;
      }
    }

    .ant-input-prefix {
      margin-right: 12px;
      color: #8c8c8c;
    }
  }

  :deep(.ant-input:not(.ant-input-affix-wrapper .ant-input)) {
    padding: 10px 15px;
    border-radius: 8px;
    border: 1px solid #d9d9d9;

    &:hover {
      border-color: #fb9a3d;
    }

    &:focus {
      border-color: #f6871f;
      box-shadow: 0 0 0 2px rgba(246, 135, 31, 0.12);
    }
  }

  :deep(.ant-select) {
    .ant-select-selector {
      padding: 6px 15px !important;
      border-radius: 8px !important;
      border: 1px solid #d9d9d9 !important;
      height: auto !important;
      min-height: 46px;
    }

    &:hover .ant-select-selector {
      border-color: #fb9a3d !important;
    }

    &.ant-select-focused .ant-select-selector {
      border-color: #f6871f !important;
      box-shadow: 0 0 0 2px rgba(246, 135, 31, 0.12) !important;
    }

    .ant-select-selection-item {
      line-height: 32px;
    }

    .ant-select-arrow {
      color: #8c8c8c;
    }
  }

  :deep(.domain-select .ant-input-affix-wrapper) {
    border: none !important;
    background: transparent !important;
    padding: 0 !important;
    width: 100% !important;
    height: auto !important;
    min-height: 0 !important;
    box-shadow: none !important;

    .ant-input {
      border: none !important;
      background: transparent !important;
      padding: 0 !important;
      height: auto !important;
      min-height: 0 !important;
      box-shadow: none !important;
    }

    .ant-input-prefix {
      margin: 0 12px 0 0 !important;
    }
  }

  button.login-button {
    margin-top: 8px;
    padding: 0 15px;
    font-size: 16px;
    font-weight: 600;
    height: 46px;
    width: 100%;
    border-radius: 8px;
    background: #f6871f;
    border: none;
    box-shadow: 0 2px 8px rgba(246, 135, 31, 0.35);
    transition: all 0.3s ease;

    &:hover:not(:disabled) {
      background: #fb9a3d;
      transform: translateY(-2px);
      box-shadow: 0 4px 14px rgba(246, 135, 31, 0.45);
    }

    &:active:not(:disabled) {
      transform: translateY(0);
      background: #e0701a;
    }
  }

  .forgot-password-link {
    color: #f6871f;
    font-weight: 500;
    transition: color 0.3s ease;

    &:hover {
      color: #e0701a;
      text-decoration: underline;
    }
  }

  .sso-section {
    margin-top: 4px;

    .or {
      margin: 24px 0 20px;
    }
  }

  .sso-button {
    display: flex;
    align-items: center;
    justify-content: center;
    height: 46px;
    font-size: 15px;
    font-weight: 500;
    border-radius: 8px;
    transition: all 0.3s ease;

    &:hover {
      color: #f6871f;
      border-color: #fb9a3d;
      transform: translateY(-1px);
      box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
    }
  }

  .oauth-section {
    margin-top: 16px;
    margin-bottom: 4px;
  }

  .field-label {
    text-align: left;
    font-size: 14px;
    color: rgba(0, 0, 0, 0.65);
    margin-bottom: 8px;
  }

  .oauth-login-button {
    display: flex;
    align-items: center;
    justify-content: center;
    height: 44px;
    margin-bottom: 10px;
    font-size: 15px;
    border-radius: 8px;

    &:hover {
      transform: translateY(-1px);
      box-shadow: 0 4px 12px rgba(0, 0, 0, 0.1);
    }
  }

  .oauth-btn-logo {
    width: 22px;
    height: 22px;
    margin-right: 10px;
  }

  .content {
    margin: 20px auto;
    width: 100%;
  }

  .or {
    display: flex;
    align-items: center;
    gap: 12px;
    text-align: center;
    font-size: 14px;
    color: #8c8c8c;

    &::before,
    &::after {
      content: '';
      flex: 1;
      height: 1px;
      background: #d9d9d9;
    }
  }
}
</style>

<style lang="less">
// The login dropdowns (domain, server, IdP) render in portals attached to <body>,
// outside the login container, so they cannot be themed by the scoped block.
// These rules override the app-wide blue dropdown hover from style/vars.less,
// which ships with !important, so !important plus a higher specificity is needed.
.ant-select-dropdown.login-dropdown {
  .ant-select-item {
    border-radius: 6px;
  }

  .ant-select-item:hover,
  .ant-select-item-option-active:not(.ant-select-item-option-disabled) {
    background-color: #fff3e6 !important;
    color: #e0701a !important;
  }

  .ant-select-item-option-selected:not(.ant-select-item-option-disabled) {
    background-color: rgba(246, 135, 31, 0.12) !important;
    color: #e0701a !important;

    &:hover,
    &.ant-select-item-option-active {
      background-color: rgba(246, 135, 31, 0.22) !important;
    }
  }
}
</style>
