import { registerRootComponent } from 'expo';

// Load shadow-cljs compiled code
require('./app/index');

// Access the exported App from expo.root namespace
const App = expo.root.App;

registerRootComponent(App);
