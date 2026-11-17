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
  <main class="about-page">
    <header class="about-page__toolbar">
      <button type="button" class="about-page__back" @click="goBack">
        <span aria-hidden="true">←</span> Back to CE Cloud
      </button>
    </header>
    <iframe class="about-page__content" :src="$route.meta.path" :title="$t($route.meta.title)"></iframe>
  </main>
</template>

<script>
export default {
  name: 'AboutPlugin',
  methods: {
    goBack () {
      const previous = this.$router.options.history.state.back
      if (previous && previous !== this.$route.fullPath) {
        this.$router.back()
      } else {
        this.$router.replace('/dashboard')
      }
    }
  }
}
</script>

<style scoped>
.about-page {
  position: fixed;
  inset: 0;
  display: flex;
  flex-direction: column;
  background: #fff;
}

.about-page__toolbar {
  flex-shrink: 0;
  padding: 8px 16px;
  border-bottom: 1px solid #e8e8e8;
  background: #fff;
}

.about-page__back {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 12px;
  border: 1px solid #d9d9d9;
  border-radius: 4px;
  background: #fff;
  color: #252525;
  font: inherit;
  cursor: pointer;
}

.about-page__back:hover {
  background: #f5f5f5;
}

.about-page__back:focus-visible {
  outline: 2px solid #1677ff;
  outline-offset: 2px;
}

.about-page__content {
  flex: 1;
  width: 100%;
  min-height: 0;
  border: 0;
}
</style>
