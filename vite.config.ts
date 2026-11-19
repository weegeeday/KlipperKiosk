import fs from 'node:fs'
import path from 'node:path'
import vue from '@vitejs/plugin-vue'
import { defineConfig, type Plugin } from 'vite'

function emitSitesJsonPlugin(): Plugin {
  return {
    name: 'emit-sites-json',
    async writeBundle(options) {
      const outDir = options.dir || path.resolve(process.cwd(), 'dist')
      const sitesPath = path.resolve(process.cwd(), 'src/config/sites.ts')
      const source = fs.readFileSync(sitesPath, 'utf-8')
      const match = source.match(/DEFAULT_SITES[^=]*=\s*(\[[\s\S]*?\]);/)
      if (!match) return
      // Evaluate the array literal safely in a Function scope
      const sites = new Function(`return ${match[1]}`)()
      fs.writeFileSync(
        path.join(outDir, 'sites.json'),
        JSON.stringify(sites, null, 2),
        'utf-8'
      )
    },
  }
}

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue(), emitSitesJsonPlugin()],
  build: {
    target: 'chrome80',
  },
})
