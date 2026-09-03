import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import '@douyinfe/semi-ui/lib/es/_base/base.css';
import './theme/semi-overrides.scss';
import { App } from './app/App';

createRoot(document.getElementById('root')!).render(
  <StrictMode><App /></StrictMode>
);
