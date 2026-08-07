FROM nginx:1.27-alpine

COPY nginx.docker.conf /etc/nginx/conf.d/default.conf
COPY *.html favicon.ico /usr/share/nginx/html/
COPY css /usr/share/nginx/html/css
COPY imgs /usr/share/nginx/html/imgs
COPY js /usr/share/nginx/html/js

EXPOSE 80