export const appName = 'BMChat'
export const homePageUrl = 'http://5.187.4.132/'
export const gitHubUrl = 'https://github.com/hvkeyn/BMChat'
export const gitHubIssuesUrl = gitHubUrl + '/issues'
export const gitHubLicenseUrl = gitHubUrl + '/blob/main/LICENSE'
// Donations were removed from BMChat; the constant points at the project page
// for any UI surfaces that still reference it through legacy code paths.
export const donationUrl = 'http://5.187.4.132/'

export const appWindowTitle = appName

export const enum Timespans {
  ZERO_SECONDS = 0,
  ONE_SECOND = 1,
  ONE_MINUTE_IN_SECONDS = 60,
  ONE_HOUR_IN_SECONDS = 60 * 60,
  ONE_DAY_IN_SECONDS = 60 * 60 * 24,
  ONE_WEEK_IN_SECONDS = 60 * 60 * 24 * 7,
  ONE_YEAR_IN_SECONDS = 60 * 60 * 24 * 365,
}

export const enum AutodeleteDuration {
  NEVER = Timespans.ZERO_SECONDS,
  AT_ONCE = Timespans.ONE_SECOND,
  ONE_HOUR = Timespans.ONE_HOUR_IN_SECONDS,
  ONE_DAY = Timespans.ONE_DAY_IN_SECONDS,
  ONE_WEEK = Timespans.ONE_WEEK_IN_SECONDS,
  FIVE_WEEKS = Timespans.ONE_WEEK_IN_SECONDS * 5,
  ONE_YEAR = Timespans.ONE_YEAR_IN_SECONDS,
}

export const IMAGE_EXTENSIONS = ['jpg', 'jpeg', 'png', 'apng', 'gif', 'webp']

export const enum NOTIFICATION_TYPE {
  MESSAGE,
  REACTION,
  WEBXDC_INFO,
}
