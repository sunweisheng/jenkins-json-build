const gulp = require('gulp');
const cleanCss = require('gulp-clean-css');
const rename = require('gulp-rename');

function styles() {
  return gulp.src('src/*.css')
    .pipe(cleanCss())
    .pipe(rename({ suffix: '.min' }))
    .pipe(gulp.dest('css'));
}

exports.styles = styles;
exports.default = styles;
