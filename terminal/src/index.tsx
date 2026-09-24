import { render } from 'ink';

import { terminalApi } from './api';
import { App } from './ui/App';

render(<App api={terminalApi} />);
