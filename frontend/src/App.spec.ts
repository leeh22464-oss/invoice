import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'

import App from './App.vue'

describe('App', () => {
  it('renders the Chinese product name', () => {
    const wrapper = mount(App)

    expect(wrapper.text()).toContain('智能核票')
  })
})
