// Philterd's brand red, from philterd.ai (--c-primary rgb(191, 80, 96) and --c-primary-dark rgb(140, 59, 70)),
// as a "brand" color for buttons, links, focus rings, and the current page in navigation. Status colors (blue
// for information, red for errors, green for success) are Tailwind's own. Loaded after tailwind.js.
tailwind.config = {
    theme: {
        extend: {
            colors: {
                brand: {
                    50: '#fdf5f6',
                    100: '#f3e0e3',
                    200: '#e3c4cb',
                    300: '#d498a3',
                    400: '#c9707e',
                    500: '#bf5060',
                    600: '#bf5060',
                    700: '#8c3b46',
                    800: '#73303a',
                    900: '#5a262d'
                }
            }
        }
    }
};
