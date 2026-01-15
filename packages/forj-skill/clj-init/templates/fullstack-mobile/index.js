// Disable Expo/Metro hot reload - shadow-cljs handles hot reload
if (typeof window !== 'undefined') {
  window.$RefreshReg$ = () => {};
  window.$RefreshSig$ = () => type => type;
  window.metroHotUpdateModule = () => {};
  window.__accept = () => {};
  if (typeof module !== 'undefined' && module.hot) {
    delete module.hot;
  }
  console.log('Metro hot reload disabled - shadow-cljs will handle hot reload');
}

import './app/index.js';
